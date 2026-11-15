package com.hmdp.utils;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisIdWorkerTest {
    @Test void distinctAcrossDaysAndCounterOverflowRejected() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        RedisIdWorker day1 = new RedisIdWorker(redis, Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC));
        RedisIdWorker day2 = new RedisIdWorker(redis, Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC));
        assertNotEquals(day1.nextId("order"), day2.nextId("order"));
        when(values.increment(anyString())).thenReturn(4294967296L);
        assertThrows(IllegalStateException.class, () -> day1.nextId("order"));
    }
    @Test void springCreatesWorkerWithAutowiredConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
            context.register(RedisIdWorker.class);
            context.refresh();
            assertNotNull(context.getBean(RedisIdWorker.class));
        }
    }
}
