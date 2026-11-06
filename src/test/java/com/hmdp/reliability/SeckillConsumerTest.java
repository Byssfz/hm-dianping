package com.hmdp.reliability;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeckillConsumerTest {
    @AfterEach void cleanup() { UserHolder.removeUser(); }
    @Test void requestReturnsExactIdAsString() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        RedisIdWorker ids = mock(RedisIdWorker.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(ids.nextId("order")).thenReturn(1234567890123456789L);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString())).thenReturn(0L);
        ReflectionTestUtils.setField(service, "redisIdWorker", ids);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        UserDTO user = new UserDTO(); user.setId(7L); UserHolder.saveUser(user);
        Result result = service.secKillVoucher(2L);
        assertTrue(result.getSuccess());
        assertEquals("1234567890123456789", result.getData());
    }
    @Test void repeatedSameOrderIsIdempotentEvenWhenClosed() {
        VoucherOrderServiceImpl service = spy(new VoucherOrderServiceImpl());
        VoucherOrder message = new VoucherOrder().setId(10L).setUserId(7L).setVoucherId(2L);
        VoucherOrder existing = new VoucherOrder().setId(10L).setUserId(7L).setVoucherId(2L).setStatus(4);
        doReturn(existing).when(service).getById(10L);
        service.createVoucherOrder(message);
        verify(service, never()).save(any());
        existing.setUserId(8L);
        assertThrows(IllegalStateException.class, () -> service.createVoucherOrder(message));
    }
    @Test void lockContentionSignalsRetry() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        RedissonClient redisson = mock(RedissonClient.class); RLock lock = mock(RLock.class);
        when(redisson.getLock("order:7")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);
        ReflectionTestUtils.setField(service, "redissonClient", redisson);
        assertThrows(IllegalStateException.class, () -> service.handleVoucherOrder(new VoucherOrder().setUserId(7L)));
    }
}
