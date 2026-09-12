package com.hmdp.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationStoreTest {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    ObjectMapper mapper = new ObjectMapper();
    ConversationStore store;

    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values);
        store = new ConversationStore(redis, mapper);
    }

    @Test void usersCannotLoadEachOthersConversation() {
        when(values.get(store.key(1L, "abc"))).thenReturn("[]");
        assertEquals(0, store.load(1L, "abc", false).size());
        assertThrows(CustomerServiceException.class, () -> store.load(2L, "abc", false));
        assertNotEquals(store.key(1L, "abc"), store.key(2L, "abc"));
    }

    @Test void locksAreAtomicAndExpire() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true, false);
        assertTrue(store.acquire(1L, "abc", "owner"));
        assertFalse(store.acquire(1L, "abc", "other"));
        verify(values).setIfAbsent(store.key(1L, "abc") + ":lock", "owner", Duration.ofSeconds(180));
    }

    @Test void saveTrimsToTenTurnsAndCommitsWithOwnershipAndTtl() {
        ArrayNode turns = mapper.createArrayNode();
        for (int i = 0; i < 12; i++) turns.addObject().put("turn", i);
        String key = store.key(1L, "abc");
        when(redis.execute(eq(ConversationStore.SAVE), eq(Arrays.asList(key + ":lock", key)),
                eq("owner"), anyString(), eq("1800"))).thenReturn(1L);
        store.save(1L, "abc", "owner", turns);
        assertEquals(10, turns.size());
        assertEquals(2, turns.get(0).path("turn").asInt());
        verify(redis).execute(ConversationStore.SAVE, Arrays.asList(key + ":lock", key), "owner", turns.toString(), "1800");
    }

    @Test void lostLockPreventsSuccessfulCommit() {
        when(redis.execute(eq(ConversationStore.SAVE), anyList(), anyString(), anyString(), anyString())).thenReturn(0L);
        assertThrows(CustomerServiceException.class, () -> store.save(1L, "abc", "expired", mapper.createArrayNode()));
        store.release(1L, "abc", "expired");
        verify(redis).execute(ConversationStore.RELEASE, Collections.singletonList(store.key(1L, "abc") + ":lock"), "expired");
    }

    @Test void corruptHistoryFailsClearly() {
        when(values.get(anyString())).thenReturn("not-json");
        assertThrows(CustomerServiceException.class, () -> store.load(1L, "abc", false));
    }
}
