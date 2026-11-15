package com.hmdp.customer;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
public class CustomerServiceTools {
    private final IShopService shops;
    private final IVoucherService vouchers;
    private final IVoucherOrderService orders;
    private final ObjectMapper mapper;

    public CustomerServiceTools(IShopService shops, IVoucherService vouchers,
                                IVoucherOrderService orders, ObjectMapper mapper) {
        this.shops = shops;
        this.vouchers = vouchers;
        this.orders = orders;
        this.mapper = mapper;
    }

    public ArrayNode definitions() {
        ArrayNode result = mapper.createArrayNode();
        result.add(definition("searchShops", "按店名搜索商铺，每页5条；有多个候选时请用户选择", "name", "page"));
        result.add(definition("getShop", "查询指定商铺的地址、营业时间等详情", "shopId"));
        result.add(definition("getShopVouchers", "查询商铺当前上架优惠券与使用规则；金额单位为元", "shopId"));
        result.add(definition("listMyOrders", "查询当前登录用户的最近订单，每页5条", "page"));
        result.add(definition("getMyOrder", "查询当前登录用户的指定订单状态", "orderId"));
        return result;
    }

    private ObjectNode definition(String name, String description, String... fields) {
        ObjectNode tool = mapper.createObjectNode().put("type", "function");
        ObjectNode function = tool.putObject("function").put("name", name).put("description", description);
        ObjectNode schema = function.putObject("parameters").put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (String field : fields) {
            ObjectNode property = properties.putObject(field);
            if ("page".equals(field)) {
                property.put("type", "integer").put("minimum", 1).put("maximum", 100);
                property.put("description", "页码，未提供时默认为1");
            } else {
                property.put("type", "string");
                if ("name".equals(field)) property.put("minLength", 1).put("maxLength", 100);
                else property.put("pattern", "^[1-9][0-9]{0,18}$");
                required.add(field);
            }
        }
        return tool;
    }

    /** userId is supplied by the authenticated request, never by model arguments. */
    public ObjectNode execute(String name, String arguments, Long userId) {
        if (userId == null) throw new CustomerServiceException("请先登录");
        try {
            if (arguments == null || arguments.length() > 4096) throw new IllegalArgumentException();
            JsonNode args = mapper.readTree(arguments);
            if (args == null || !args.isObject()) throw new IllegalArgumentException();
            switch (name) {
                case "searchShops": {
                    allowed(args, "name", "page");
                    JsonNode value = args.path("name");
                    if (!value.isTextual() || value.asText().trim().isEmpty() || value.asText().length() > 100)
                        throw new IllegalArgumentException();
                    String keyword = value.asText().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                    Page<Shop> page = shops.page(new Page<>(page(args), 5),
                            new QueryWrapper<Shop>().like("name", keyword).orderByAsc("id"));
                    ObjectNode data = pageResult(page.getCurrent(), page.getTotal());
                    ArrayNode items = data.putArray("items");
                    for (Shop shop : page.getRecords()) items.add(shopView(shop));
                    return data;
                }
                case "getShop": {
                    allowed(args, "shopId");
                    Result result = shops.queryByID(id(args.path("shopId")));
                    if (!Boolean.TRUE.equals(result.getSuccess()) || result.getData() == null) return error("未找到商铺");
                    return shopView((Shop) result.getData());
                }
                case "getShopVouchers": {
                    allowed(args, "shopId");
                    Long shopId = id(args.path("shopId"));
                    Result result = vouchers.queryVoucherOfShop(shopId);
                    if (!Boolean.TRUE.equals(result.getSuccess())) return error("未查到优惠券信息");
                    ObjectNode data = mapper.createObjectNode().put("shopId", shopId.toString());
                    ArrayNode items = data.putArray("items");
                    List<?> list = result.getData() == null ? Collections.emptyList() : (List<?>) result.getData();
                    // Bound model context even when a shop has many vouchers.
                    for (Object value : list.subList(0, Math.min(20, list.size()))) items.add(voucherView((Voucher) value));
                    data.put("truncated", list.size() > 20);
                    return data;
                }
                case "listMyOrders": {
                    allowed(args, "page");
                    Page<VoucherOrder> page = orders.page(new Page<>(page(args), 5),
                            new QueryWrapper<VoucherOrder>().eq("user_id", userId).orderByDesc("create_time", "id"));
                    ObjectNode data = pageResult(page.getCurrent(), page.getTotal());
                    ArrayNode items = data.putArray("items");
                    for (VoucherOrder order : page.getRecords()) {
                        if (userId.equals(order.getUserId())) items.add(orderView(order));
                    }
                    return data;
                }
                case "getMyOrder": {
                    allowed(args, "orderId");
                    VoucherOrder order = orders.getOne(new QueryWrapper<VoucherOrder>()
                            .eq("id", id(args.path("orderId"))).eq("user_id", userId));
                    if (order == null || !userId.equals(order.getUserId())) return error("未找到可访问的订单");
                    return orderView(order);
                }
                default:
                    return error("不支持的工具，仅能使用已声明的查询工具");
            }
        } catch (IllegalArgumentException | JsonProcessingException e) {
            return error("工具参数无效，请使用声明的参数、正整数ID及1至100的页码");
        }
    }

    private void allowed(JsonNode args, String... keys) {
        Set<String> names = new HashSet<>(Arrays.asList(keys));
        args.fieldNames().forEachRemaining(key -> { if (!names.contains(key)) throw new IllegalArgumentException(); });
    }

    static long id(JsonNode value) {
        if (!value.isTextual() || !value.asText().matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
        return Long.parseLong(value.asText());
    }

    private int page(JsonNode args) {
        JsonNode value = args.get("page");
        if (value == null) return 1;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 100)
            throw new IllegalArgumentException();
        return value.intValue();
    }

    private ObjectNode pageResult(long page, long total) {
        return mapper.createObjectNode().put("page", page).put("total", total).put("hasMore", page * 5 < total);
    }

    private ObjectNode shopView(Shop shop) {
        ObjectNode data = mapper.createObjectNode();
        data.put("shopId", String.valueOf(shop.getId()));
        data.put("name", shop.getName());
        data.put("area", shop.getArea());
        data.put("address", shop.getAddress());
        data.put("openHours", shop.getOpenHours());
        if (shop.getAvgPrice() != null) data.put("averagePriceYuan", shop.getAvgPrice());
        if (shop.getScore() != null) data.put("score", BigDecimal.valueOf(shop.getScore(), 1));
        return data;
    }

    private ObjectNode voucherView(Voucher voucher) {
        ObjectNode data = mapper.createObjectNode().put("voucherId", String.valueOf(voucher.getId()));
        data.put("shopId", String.valueOf(voucher.getShopId()));
        data.put("title", voucher.getTitle());
        data.put("rules", voucher.getRules());
        if (voucher.getPayValue() != null) data.put("payYuan", BigDecimal.valueOf(voucher.getPayValue(), 2).toPlainString());
        if (voucher.getActualValue() != null) data.put("deductionYuan", BigDecimal.valueOf(voucher.getActualValue(), 2).toPlainString());
        putTime(data, "seckillBeginTime", voucher.getBeginTime());
        putTime(data, "seckillEndTime", voucher.getEndTime());
        // Stock and flash-sale dates do not establish redemption validity or a stock reservation.
        return data;
    }

    private ObjectNode orderView(VoucherOrder order) {
        String[] states = {"未知", "未支付", "已支付", "已核销", "已取消", "退款中", "已退款"};
        Integer status = order.getStatus();
        ObjectNode data = mapper.createObjectNode().put("orderId", String.valueOf(order.getId()));
        data.put("voucherId", String.valueOf(order.getVoucherId()));
        data.put("status", status != null && status > 0 && status < states.length ? states[status] : "未知");
        putTime(data, "createTime", order.getCreateTime());
        putTime(data, "payTime", order.getPayTime());
        putTime(data, "useTime", order.getUseTime());
        putTime(data, "refundTime", order.getRefundTime());
        return data;
    }

    private void putTime(ObjectNode data, String key, Object value) {
        if (value != null) data.put(key, value.toString());
    }

    private ObjectNode error(String message) { return mapper.createObjectNode().put("error", message); }

    /** Store only reference identifiers/labels for disambiguation, never old prices or statuses. */
    public ArrayNode references(JsonNode result) {
        ArrayNode references = mapper.createArrayNode();
        collectReferences(result, references);
        return references;
    }

    private void collectReferences(JsonNode value, ArrayNode references) {
        if (value.isObject()) {
            ObjectNode ref = mapper.createObjectNode();
            for (String key : new String[]{"shopId", "voucherId", "orderId", "name", "title"}) {
                if (value.hasNonNull(key)) ref.set(key, value.get(key));
            }
            if (ref.size() > 0) references.add(ref);
            if (value.has("items")) collectReferences(value.get("items"), references);
        } else if (value.isArray()) {
            value.forEach(item -> collectReferences(item, references));
        }
    }
}
