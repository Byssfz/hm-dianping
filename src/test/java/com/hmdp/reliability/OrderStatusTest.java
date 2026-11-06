package com.hmdp.reliability;

import com.hmdp.controller.VoucherOrderController;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderStatusTest {
    @AfterEach void cleanup() { UserHolder.removeUser(); }
    @Test void statusIsPrivateAndConsistentForPendingAndCreated() {
        VoucherOrderController controller = new VoucherOrderController();
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(controller, "voucherOrderService", orders);
        ReflectionTestUtils.setField(controller, "redis", redis);
        UserDTO user = new UserDTO(); user.setId(7L); UserHolder.saveUser(user);
        when(values.get("seckill:reservation:101")).thenReturn("2:7");
        Map<?,?> pending = (Map<?,?>) controller.status(101L).getData();
        assertEquals("101", pending.get("orderId")); assertEquals("PENDING", pending.get("state"));
        when(orders.getById(101L)).thenReturn(new VoucherOrder().setId(101L).setUserId(7L).setVoucherId(2L).setStatus(4));
        Map<?,?> created = (Map<?,?>) controller.status(101L).getData();
        assertEquals("101", created.get("orderId")); assertEquals("CREATED", created.get("state")); assertEquals(4, created.get("orderStatus"));
        user.setId(8L);
        assertFalse(controller.status(101L).getSuccess());
        when(orders.getById(101L)).thenReturn(null);
        assertFalse(controller.status(101L).getSuccess());
    }
}
