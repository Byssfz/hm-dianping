package com.hmdp.ratelimit;

import com.hmdp.controller.VoucherOrderController;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeckillRateLimitAspectTest {
    private AnnotationConfigApplicationContext context;
    private StringRedisTemplate redis;
    private IVoucherOrderService service;
    private VoucherOrderController controller;

    @Configuration
    @EnableAspectJAutoProxy
    static class Config {
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean IVoucherOrderService service() { return mock(IVoucherOrderService.class); }
        @Bean SeckillRateLimitAspect aspect(StringRedisTemplate redis) {
            return new SeckillRateLimitAspect(redis, 10000, 1000, 10);
        }
        @Bean VoucherOrderController controller() { return new VoucherOrderController(); }
    }

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        redis = context.getBean(StringRedisTemplate.class);
        service = context.getBean(IVoucherOrderService.class);
        controller = context.getBean(VoucherOrderController.class);
        clearInvocations(redis, service);
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);
    }
    @AfterEach void cleanup() { UserHolder.removeUser(); context.close(); }

    @Test void actualSpringProxyPassesAllowedAndBlocksUserAndGlobalLimits() {
        assertTrue(AopUtils.isAopProxy(controller));
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(1L, 2L, 0L);
        when(service.secKillVoucher(5L)).thenReturn(Result.ok(88L));
        assertTrue(controller.seckillVoucher(5L).getSuccess());
        assertFalse(controller.seckillVoucher(5L).getSuccess());
        assertFalse(controller.seckillVoucher(5L).getSuccess());
        verify(service, times(1)).secKillVoucher(5L);
        verify(redis, times(3)).execute(any(RedisScript.class),
                eq(java.util.Arrays.asList("rate:seckill:{seckill}:global", "rate:seckill:{seckill}:user:42", "rate:seckill:{seckill}:sequence")),
                eq("10000"), eq("1000"), eq("10"));
    }

    @Test void redisFailureAndInvalidResultFailClosed() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("unavailable")).thenReturn(null).thenReturn(9L);
        for (int i = 0; i < 3; i++) {
            Result result = controller.seckillVoucher(5L);
            assertFalse(result.getSuccess());
            assertEquals("限流服务暂时不可用，请稍后重试", result.getErrorMsg());
        }
        verifyNoInteractions(service);
    }

    @Test void missingIdentityDoesNotCallRedisOrService() {
        UserHolder.removeUser();
        assertEquals("请先登录", controller.seckillVoucher(5L).getErrorMsg());
        verifyNoInteractions(redis, service);
    }

    @Test void environmentPropertiesOverrideDefaults() {
        AnnotationConfigApplicationContext configured = new AnnotationConfigApplicationContext();
        java.util.Map<String, Object> env = new java.util.HashMap<>();
        env.put("HMDP_RATE_LIMIT_SECKILL_WINDOW_MILLIS", "1500");
        env.put("HMDP_RATE_LIMIT_SECKILL_GLOBAL_LIMIT", "20");
        env.put("HMDP_RATE_LIMIT_SECKILL_USER_LIMIT", "3");
        configured.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.SystemEnvironmentPropertySource("testEnv", env));
        configured.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
        configured.register(SeckillRateLimitAspect.class);
        configured.refresh();
        try {
            SeckillRateLimitAspect aspect = configured.getBean(SeckillRateLimitAspect.class);
            assertEquals(1500L, org.springframework.test.util.ReflectionTestUtils.getField(aspect, "windowMillis"));
            assertEquals(20L, org.springframework.test.util.ReflectionTestUtils.getField(aspect, "globalLimit"));
            assertEquals(3L, org.springframework.test.util.ReflectionTestUtils.getField(aspect, "userLimit"));
        } finally { configured.close(); }
    }

    @Test void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SeckillRateLimitAspect(redis, 0, 1, 1).validateConfiguration());
        assertThrows(IllegalArgumentException.class, () -> new SeckillRateLimitAspect(redis, 1, 0, 1).validateConfiguration());
        assertThrows(IllegalArgumentException.class, () -> new SeckillRateLimitAspect(redis, 1, 1, 0).validateConfiguration());
    }
}
