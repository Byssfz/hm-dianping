package com.hmdp.reliability;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
@EnableScheduling
public class SeckillOutboxRelay {
    private final StringRedisTemplate redis;
    private final RocketMQTemplate mq;
    private final IVoucherOrderService orders;
    private final RedissonClient redisson;
    private String cursor = "0-0";
    public SeckillOutboxRelay(StringRedisTemplate redis, RocketMQTemplate mq, IVoucherOrderService orders, RedissonClient redisson) {
        this.redis = redis; this.mq = mq; this.orders = orders; this.redisson = redisson;
    }
    @Scheduled(fixedDelayString = "${hmdp.seckill-outbox.interval-ms:5000}")
    public void dispatch() {
        RLock lock = redisson.getLock("seckill:outbox:relay:lock");
        if (!lock.tryLock()) return;
        try {
            List<MapRecord<String,Object,Object>> records = redis.opsForStream().read(StreamReadOptions.empty().count(100),
                    StreamOffset.create("stream.orders.reliable.v1", ReadOffset.from(cursor)));
            if (records == null || records.isEmpty()) { cursor = "0-0"; return; }
            for (MapRecord<String,Object,Object> record : records) {
                cursor = record.getId().getValue();
                try { process(record); } catch (RuntimeException e) {
                    log.error("seckill outbox delivery failed record={}", record.getId(), e);
                }
            }
        } finally { lock.unlock(); }
    }
    void process(MapRecord<String,Object,Object> record) {
        Map<Object,Object> values = record.getValue();
        long id = Long.parseLong(values.get("id").toString());
        long user = Long.parseLong(values.get("userId").toString());
        long voucher = Long.parseLong(values.get("voucherId").toString());
        VoucherOrder existing = orders.getById(id);
        if (existing != null) {
            if (!Long.valueOf(user).equals(existing.getUserId()) || !Long.valueOf(voucher).equals(existing.getVoucherId()))
                throw new IllegalStateException("outbox order id collision " + id);
            redis.opsForStream().delete("stream.orders.reliable.v1", record.getId());
            return;
        }
        String reservation = redis.opsForValue().get("seckill:reservation:" + id);
        if (!(voucher + ":" + user).equals(reservation)) {
            log.error("incomplete seckill reservation requires reconciliation order={}", id);
            return;
        }
        VoucherOrder order = new VoucherOrder();
        order.setId(id); order.setUserId(user); order.setVoucherId(voucher);
        mq.syncSend("hmseckillTopic", JSONUtil.toJsonStr(order));
        // Keep intent until a subsequent scan confirms the order in MySQL.
    }
}
