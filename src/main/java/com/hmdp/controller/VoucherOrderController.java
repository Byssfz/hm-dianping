package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.LinkedHashMap;
import java.util.Map;
import com.hmdp.ratelimit.SeckillRateLimited;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.impl.VoucherServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Autowired
    private IVoucherOrderService voucherOrderService;
    @Autowired
    private StringRedisTemplate redis;
    @GetMapping("/{id}/status")
    public Result status(@PathVariable("id") Long id) {
        if (UserHolder.getUser() == null || UserHolder.getUser().getId() == null) return Result.fail("请先登录");
        Long user = UserHolder.getUser().getId();
        VoucherOrder order = voucherOrderService.getById(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", id.toString());
        if (order != null) {
            if (!user.equals(order.getUserId())) return Result.fail("订单不存在");
            result.put("state", "CREATED");
            result.put("orderStatus", order.getStatus());
            return Result.ok(result);
        }
        String reservation = redis.opsForValue().get("seckill:reservation:" + id);
        if (reservation == null || !reservation.matches("[1-9][0-9]*:" + user)) return Result.fail("订单不存在");
        result.put("state", "PENDING");
        result.put("orderStatus", null);
        return Result.ok(result);
    }
    @SeckillRateLimited
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {

        return voucherOrderService.secKillVoucher(voucherId);
    }
}
