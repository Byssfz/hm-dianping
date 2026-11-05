package com.hmdp.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/** Per-user hourly quota so a single account cannot drive unlimited model spend. */
@Slf4j
@Component
public class AiQuota {
    static final int LIMIT_PER_HOUR = 20;
    static final int TTL_SECONDS = 3600;
    private static final String KEY_PREFIX = "ai:quota:blog-draft:";
    // INCR and EXPIRE must happen together, otherwise a crash between them leaves a key that never expires.
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>(
            "local n = redis.call('incr', KEYS[1]) "
                    + "if n == 1 then redis.call('expire', KEYS[1], ARGV[1]) end "
                    + "return n", Long.class);

    private final StringRedisTemplate redis;

    public AiQuota(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void consume(Long userId) {
        if (userId == null) throw new AiException("请先登录");
        try {
            Long used = redis.execute(CONSUME, Collections.singletonList(KEY_PREFIX + userId),
                    String.valueOf(TTL_SECONDS));
            if (used != null && used > LIMIT_PER_HOUR) {
                throw new AiException("AI 文案生成次数已达上限，请稍后再试");
            }
        } catch (DataAccessException e) {
            // Quota is a cost guard, not a business invariant: keep the feature usable if Redis is down.
            log.warn("ai quota unavailable category={}", e.getClass().getSimpleName());
        }
    }
}
