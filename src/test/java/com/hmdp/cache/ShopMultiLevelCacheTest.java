package com.hmdp.cache;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopMultiLevelCacheTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final Map<String, String> redisData = new HashMap<>();
    private final AtomicLong ticker = new AtomicLong();
    private final Cache<Long, Shop> local = Caffeine.newBuilder().maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.SECONDS).ticker(ticker::get).build();
    private ShopServiceImpl shops;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(inv -> redisData.get(inv.getArgument(0)));
        doAnswer(inv -> { redisData.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redis.delete(anyString())).thenAnswer(inv -> redisData.remove(inv.getArgument(0)) != null);
        shops = spy(new ShopServiceImpl());
        ReflectionTestUtils.setField(shops, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(shops, "cacheClient", new CacheClient(redis));
        ReflectionTestUtils.setField(shops, "shopLocalCache", local);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Shop shop(String name) { return new Shop().setId(1L).setName(name); }
    private String name(Result result) { return ((Shop) result.getData()).getName(); }

    @Test
    void databaseResultPopulatesBothCachesAndNextReadUsesLocalCache() {
        doReturn(shop("database")).when(shops).getById(1L);
        assertEquals("database", name(shops.queryByID(1L)));
        assertEquals("database", name(shops.queryByID(1L)));
        verify(shops, times(1)).getById(1L);
        verify(values, times(1)).get(CACHE_SHOP_KEY + 1);
        verify(values).set(eq(CACHE_SHOP_KEY + 1), anyString(), eq(30L), eq(TimeUnit.MINUTES));
        assertNotNull(local.getIfPresent(1L));
    }

    @Test
    void redisHitPopulatesLocalCacheWithoutDatabaseQuery() {
        redisData.put(CACHE_SHOP_KEY + 1, JSONUtil.toJsonStr(shop("redis")));
        assertEquals("redis", name(shops.queryByID(1L)));
        redisData.clear();
        assertEquals("redis", name(shops.queryByID(1L)));
        verify(shops, never()).getById(any());
        verify(values, times(1)).get(CACHE_SHOP_KEY + 1);
    }

    @Test
    void missingShopIsNegativelyCachedOnlyInRedis() {
        doReturn(null).when(shops).getById(1L);
        assertFalse(shops.queryByID(1L).getSuccess());
        assertFalse(shops.queryByID(1L).getSuccess());
        verify(shops, times(1)).getById(1L);
        verify(values).set(eq(CACHE_SHOP_KEY + 1), eq(""), eq(2L), eq(TimeUnit.MINUTES));
        assertNull(local.getIfPresent(1L));
    }

    @Test
    void localExpirationFallsBackToRedis() {
        local.put(1L, shop("old local"));
        redisData.put(CACHE_SHOP_KEY + 1, JSONUtil.toJsonStr(shop("redis")));
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertEquals("redis", name(shops.queryByID(1L)));
        verify(shops, never()).getById(any());
    }

    @Test
    void successfulUpdateInvalidatesCachesOnlyAfterCommit() {
        Shop update = shop("new");
        local.put(1L, shop("old"));
        redisData.put(CACHE_SHOP_KEY + 1, "old");
        doReturn(true).when(shops).updateById(update);
        TransactionSynchronizationManager.initSynchronization();
        assertTrue(shops.update(update).getSuccess());
        assertNotNull(local.getIfPresent(1L));
        verify(redis, never()).delete(anyString());
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();
        assertNull(local.getIfPresent(1L));
        assertFalse(redisData.containsKey(CACHE_SHOP_KEY + 1));
        verify(redis).delete(CACHE_SHOP_KEY + 1);
    }

    @Test
    void rolledBackUpdateDoesNotInvalidateCaches() {
        Shop update = shop("new");
        local.put(1L, shop("old"));
        redisData.put(CACHE_SHOP_KEY + 1, "old");
        doReturn(true).when(shops).updateById(update);
        TransactionSynchronizationManager.initSynchronization();
        shops.update(update);
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        assertNotNull(local.getIfPresent(1L));
        assertTrue(redisData.containsKey(CACHE_SHOP_KEY + 1));
        verify(redis, never()).delete(anyString());
    }
    @Test
    void redisDeleteFailureStillEvictsLocalCacheAfterCommit() {
        Shop update = shop("new");
        local.put(1L, shop("old"));
        doReturn(true).when(shops).updateById(update);
        when(redis.delete(CACHE_SHOP_KEY + 1)).thenThrow(new IllegalStateException("redis unavailable"));
        TransactionSynchronizationManager.initSynchronization();
        assertTrue(shops.update(update).getSuccess());
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();
        assertNull(local.getIfPresent(1L));
    }

    @Test
    void unsuccessfulUpdateDoesNotScheduleInvalidation() {
        Shop update = shop("new");
        local.put(1L, shop("old"));
        doReturn(false).when(shops).updateById(update);
        TransactionSynchronizationManager.initSynchronization();
        assertFalse(shops.update(update).getSuccess());
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        assertNotNull(local.getIfPresent(1L));
        verify(redis, never()).delete(anyString());
    }
    @Test
    void callerChangesDoNotContaminateCachedShop() {
        doReturn(shop("original")).when(shops).getById(1L);
        Shop first = (Shop) shops.queryByID(1L).getData();
        first.setName("changed by caller");
        Shop second = (Shop) shops.queryByID(1L).getData();
        assertEquals("original", second.getName());
        second.setName("another change");
        assertEquals("original", name(shops.queryByID(1L)));
    }
}
