package com.hmdp.ai;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiQuotaTest {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    AiQuota quota = new AiQuota(redis);

    @Test void allowsCallsUpToTheHourlyLimit() {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(20L);
        assertDoesNotThrow(() -> quota.consume(7L));
    }

    @Test void rejectsCallsOverTheHourlyLimit() {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(21L);
        assertEquals("AI 文案生成次数已达上限，请稍后再试",
                assertThrows(AiException.class, () -> quota.consume(7L)).getMessage());
    }

    @Test void failsOpenWhenRedisIsUnavailable() {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new DataAccessResourceFailureException("secret-host"));
        assertDoesNotThrow(() -> quota.consume(7L));
    }

    @Test void anonymousRequestIsRejected() {
        assertEquals("请先登录", assertThrows(AiException.class, () -> quota.consume(null)).getMessage());
        verifyNoInteractions(redis);
    }
}
