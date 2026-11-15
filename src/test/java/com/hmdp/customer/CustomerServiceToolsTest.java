package com.hmdp.customer;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerServiceToolsTest {
    IShopService shops = mock(IShopService.class);
    IVoucherService vouchers = mock(IVoucherService.class);
    IVoucherOrderService orders = mock(IVoucherOrderService.class);
    CustomerServiceTools tools;

    @BeforeEach void setup() { tools = new CustomerServiceTools(shops, vouchers, orders, new ObjectMapper()); }

    @Test void orderQueryIncludesAuthenticatedOwnerAndUsesStringIds() {
        when(orders.getOne(any(Wrapper.class))).thenReturn(new VoucherOrder().setId(9007199254740993L)
                .setUserId(7L).setVoucherId(8L).setStatus(2));
        ObjectNode result = tools.execute("getMyOrder", "{\"orderId\":\"9007199254740993\"}", 7L);
        ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(orders).getOne(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id"));
        assertTrue(sql.contains("id"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(7L));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(9007199254740993L));
        assertEquals("已支付", result.path("status").asText());
        assertEquals("9007199254740993", result.path("orderId").textValue());
        assertFalse(result.has("userId"));
        assertFalse(result.has("payType"));
    }

    @Test void missingAndForeignOrderHaveSameResponse() {
        when(orders.getOne(any(Wrapper.class))).thenReturn(null,
                new VoucherOrder().setId(1L).setUserId(99L));
        assertEquals(tools.execute("getMyOrder", "{\"orderId\":\"1\"}", 7L),
                tools.execute("getMyOrder", "{\"orderId\":\"1\"}", 7L));
    }

    @Test void invalidOrInjectedParametersNeverReachServices() {
        for (String args : Arrays.asList("{\"orderId\":\"1\",\"userId\":99}", "{\"orderId\":-1}",
                "{\"orderId\":\"9223372036854775808\"}", "not json", "[]")) {
            assertTrue(tools.execute("getMyOrder", args, 7L).has("error"));
        }
        assertTrue(tools.execute("listMyOrders", "{\"page\":101}", 7L).has("error"));
        assertTrue(tools.execute("listMyOrders", "{\"page\":1.5}", 7L).has("error"));
        assertTrue(tools.execute("refundOrder", "{}", 7L).has("error"));
        assertThrows(CustomerServiceException.class, () -> tools.execute("listMyOrders", "{}", null));
        verifyNoInteractions(shops, vouchers, orders);
    }

    @Test void voucherMoneyUsesYuanAndHistoryOmitsDynamicFacts() {
        Voucher voucher = new Voucher().setId(8L).setShopId(1L).setTitle("代金券")
                .setRules("工作日使用").setPayValue(1990L).setActualValue(3000L);
        when(vouchers.queryVoucherOfShop(1L)).thenReturn(Result.ok(Collections.singletonList(voucher)));
        ObjectNode result = tools.execute("getShopVouchers", "{\"shopId\":\"1\"}", 7L);
        assertEquals("19.90", result.path("items").get(0).path("payYuan").asText());
        assertEquals("30.00", result.path("items").get(0).path("deductionYuan").asText());
        String references = tools.references(result).toString();
        assertTrue(references.contains("voucherId"));
        assertFalse(references.contains("payYuan"));
        assertFalse(references.contains("rules"));
    }

    @Test void shopSearchIsBoundedAndReturnsCandidates() {
        when(shops.page(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<Shop> page = invocation.getArgument(0);
            assertEquals(5, page.getSize());
            page.setTotal(6);
            page.setRecords(Arrays.asList(new Shop().setId(1L).setName("茶店一"), new Shop().setId(2L).setName("茶店二")));
            return page;
        });
        ObjectNode result = tools.execute("searchShops", "{\"name\":\"茶店\"}", 7L);
        assertEquals(2, result.path("items").size());
        assertTrue(result.path("hasMore").asBoolean());
        assertEquals("2", tools.references(result).get(1).path("shopId").asText());
    }

    @Test void shopDetailsReuseCacheAwareServiceAndHandleMissing() {
        when(shops.queryByID(1L)).thenReturn(Result.ok(new Shop().setId(1L).setName("茶店").setOpenHours("10:00-22:00")),
                Result.fail("店铺不存在"));
        assertEquals("10:00-22:00", tools.execute("getShop", "{\"shopId\":\"1\"}", 7L).path("openHours").asText());
        assertTrue(tools.execute("getShop", "{\"shopId\":\"1\"}", 7L).has("error"));
    }

    @Test void recentOrdersAlwaysFilterOwnerAndSortStably() {
        when(orders.page(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            QueryWrapper<VoucherOrder> query = invocation.getArgument(1);
            assertTrue(query.getSqlSegment().contains("ORDER BY create_time DESC,id DESC"));
            assertTrue(query.getParamNameValuePairs().containsValue(7L));
            Page<VoucherOrder> page = invocation.getArgument(0);
            assertEquals(5, page.getSize());
            return page;
        });
        assertEquals(0, tools.execute("listMyOrders", "{}", 7L).path("items").size());
    }
}
