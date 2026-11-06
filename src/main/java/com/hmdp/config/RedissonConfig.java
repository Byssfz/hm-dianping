package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.redisson.config.SingleServerConfig;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(RedisProperties redis){
        return Redisson.create(buildConfig(redis));
    }

    static Config buildConfig(RedisProperties redis) {
        Config config=new Config();
        String scheme = redis.isSsl() ? "rediss://" : "redis://";
        SingleServerConfig server = config.useSingleServer().setAddress(scheme + redis.getHost() + ":" + redis.getPort())
                .setDatabase(redis.getDatabase());
        if (redis.getPassword() != null && !redis.getPassword().isEmpty()) server.setPassword(redis.getPassword());
        return config;
    }
}
