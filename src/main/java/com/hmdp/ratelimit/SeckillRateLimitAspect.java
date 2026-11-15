package com.hmdp.ratelimit;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Arrays;

@Aspect
@Component
@Slf4j
public class SeckillRateLimitAspect {
    private static final String PREFIX = "rate:seckill:{seckill}:";
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("seckill-rate-limit.lua"));
        SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final long windowMillis;
    private final long globalLimit;
    private final long userLimit;

    public SeckillRateLimitAspect(StringRedisTemplate redis,
            @Value("${hmdp.rate-limit.seckill.window-millis:10000}") long windowMillis,
            @Value("${hmdp.rate-limit.seckill.global-limit:1000}") long globalLimit,
            @Value("${hmdp.rate-limit.seckill.user-limit:10}") long userLimit) {
        this.redis = redis;
        this.windowMillis = windowMillis;
        this.globalLimit = globalLimit;
        this.userLimit = userLimit;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (windowMillis <= 0 || globalLimit <= 0 || userLimit <= 0) {
            throw new IllegalArgumentException("Flash-sale rate limits and window must be positive");
        }
    }

    @Around("@annotation(com.hmdp.ratelimit.SeckillRateLimited)")
    public Object limit(ProceedingJoinPoint joinPoint) throws Throwable {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) return Result.fail("请先登录");

        final Long allowed;
        try {
            allowed = redis.execute(SCRIPT,
                    Arrays.asList(PREFIX + "global", PREFIX + "user:" + user.getId(), PREFIX + "sequence"),
                    Long.toString(windowMillis), Long.toString(globalLimit), Long.toString(userLimit));
        } catch (RuntimeException e) {
            log.warn("秒杀限流服务不可用", e);
            return Result.fail("限流服务暂时不可用，请稍后重试");
        }
        if (allowed == null || (allowed != 0L && allowed != 1L && allowed != 2L)) {
            log.warn("秒杀限流脚本返回无效结果");
            return Result.fail("限流服务暂时不可用，请稍后重试");
        }
        if (allowed != 1L) return Result.fail("请求过于频繁，请稍后重试");
        return joinPoint.proceed();
    }
}
