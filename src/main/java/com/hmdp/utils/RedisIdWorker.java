package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    public static final int COUNT_BITS = 32;
    private final StringRedisTemplate redis;
    private final Clock clock;
    @Autowired
    public RedisIdWorker(StringRedisTemplate redis) { this(redis, Clock.systemUTC()); }
    RedisIdWorker(StringRedisTemplate redis, Clock clock) { this.redis = redis; this.clock = clock; }
    public long nextId(String keyPrefix) {
        Instant now = clock.instant();
        long timestamp = now.getEpochSecond() - BEGIN_TIMESTAMP;
        // The old implementation used BEGIN_TIMESTAMP as the upper 32 bits.
        if (timestamp <= 0 || timestamp > Integer.MAX_VALUE || timestamp == BEGIN_TIMESTAMP)
            throw new IllegalStateException("ID timestamp out of supported range");
        String day = DateTimeFormatter.ofPattern("yyyy:MM:dd").withZone(ZoneOffset.UTC).format(now);
        Long count = redis.opsForValue().increment("icr:v2:" + keyPrefix + ":" + day);
        if (count == null || count <= 0 || count > 0xffffffffL)
            throw new IllegalStateException("ID daily counter exhausted or unavailable");
        return timestamp << COUNT_BITS | count;
    }
}
