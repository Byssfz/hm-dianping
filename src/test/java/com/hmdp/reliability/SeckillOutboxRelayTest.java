package com.hmdp.reliability;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.HashMap;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SeckillOutboxRelayTest {
    @Test void keepIntentUntilMatchingDatabaseOrderExists() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        StreamOperations<String,Object,Object> stream = mock(StreamOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForStream()).thenReturn(stream);
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        SeckillOutboxRelay relay = new SeckillOutboxRelay(redis, mq, orders, mock(RedissonClient.class));
        Map<String,String> data = new HashMap<>();
        data.put("id", "101"); data.put("userId", "7"); data.put("voucherId", "2");
        MapRecord<String,Object,Object> record = StreamRecords.<String,Object,Object>mapBacked((Map)data).withStreamKey("stream.orders.reliable.v1").withId(RecordId.of("1-0"));
        when(values.get("seckill:reservation:101")).thenReturn("2:7");
        relay.process(record);
        verify(mq).syncSend(eq("hmseckillTopic"), anyString());
        verify(stream, never()).delete(anyString(), any(RecordId.class));
        when(orders.getById(101L)).thenReturn(new VoucherOrder().setId(101L).setUserId(7L).setVoucherId(2L));
        relay.process(record);
        verify(stream).delete("stream.orders.reliable.v1", RecordId.of("1-0"));
    }
    @Test void missingReservationNeverSends() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        SeckillOutboxRelay relay = new SeckillOutboxRelay(redis, mq, mock(IVoucherOrderService.class), mock(RedissonClient.class));
        Map<String,String> data = new HashMap<>();
        data.put("id", "101"); data.put("userId", "7"); data.put("voucherId", "2");
        MapRecord<String,Object,Object> record = StreamRecords.<String,Object,Object>mapBacked((Map)data).withStreamKey("stream.orders.reliable.v1").withId(RecordId.of("1-0"));
        relay.process(record);
        verifyNoInteractions(mq);
    }
}
