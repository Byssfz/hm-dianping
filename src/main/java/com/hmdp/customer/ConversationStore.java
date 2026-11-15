package com.hmdp.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

@Component
public class ConversationStore {
    static final int TTL_SECONDS = 1800;
    static final int LOCK_SECONDS = 180;
    static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end "
                    + "redis.call('set', KEYS[2], ARGV[2], 'EX', ARGV[3]); "
                    + "redis.call('del', KEYS[1]); return 1", Long.class);
    static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0", Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ConversationStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    String key(Long userId, String conversationId) {
        // Shared hash tag keeps the atomic script compatible with Redis Cluster.
        return "customer-service:{" + userId + ":" + conversationId + "}";
    }

    public boolean acquire(Long userId, String conversationId, String token) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(userId, conversationId) + ":lock",
                token, Duration.ofSeconds(LOCK_SECONDS)));
    }

    public ArrayNode load(Long userId, String conversationId, boolean newConversation) {
        if (newConversation) return mapper.createArrayNode();
        String json = redis.opsForValue().get(key(userId, conversationId));
        if (json == null) throw new CustomerServiceException("会话不存在或已过期，请不传conversationId重新开始");
        try {
            JsonNode history = mapper.readTree(json);
            if (history == null || !history.isArray()) throw new CustomerServiceException("会话数据异常，请重新开始");
            return (ArrayNode) history;
        } catch (JsonProcessingException e) {
            throw new CustomerServiceException("会话数据异常，请重新开始");
        }
    }

    public void save(Long userId, String conversationId, String token, ArrayNode turns) {
        while (turns.size() > 10) turns.remove(0);
        String key = key(userId, conversationId);
        Long saved = redis.execute(SAVE, Arrays.asList(key + ":lock", key),
                token, turns.toString(), String.valueOf(TTL_SECONDS));
        if (!Long.valueOf(1).equals(saved)) throw new CustomerServiceException("本次处理已超时，请重新发送问题");
    }

    public void release(Long userId, String conversationId, String token) {
        redis.execute(RELEASE, Collections.singletonList(key(userId, conversationId) + ":lock"), token);
    }
}
