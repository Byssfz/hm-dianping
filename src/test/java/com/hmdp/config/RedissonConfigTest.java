package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import static org.junit.jupiter.api.Assertions.*;

class RedissonConfigTest {
    @Test void followsSpringRedisPropertiesWithoutConnecting() {
        RedisProperties props = new RedisProperties();
        props.setHost("127.0.0.2"); props.setPort(16380); props.setDatabase(3);
        props.setPassword("test-only"); props.setSsl(true);
        SingleServerConfig server = RedissonConfig.buildConfig(props).useSingleServer();
        assertEquals("rediss://127.0.0.2:16380", server.getAddress());
        assertEquals(3, server.getDatabase()); assertEquals("test-only", server.getPassword());
    }
}
