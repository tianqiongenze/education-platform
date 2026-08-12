package com.ep.cache;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CacheServiceTest {
    private CacheService svc;
    private CacheManager cm;
    private RedisTemplate<String, Object> redis;
    private CacheBroadcast broadcast;

    @BeforeEach
    void setup() {
        cm = mock(CacheManager.class);
        redis = mock(RedisTemplate.class);
        broadcast = mock(CacheBroadcast.class);
        when(cm.getCache(anyString())).thenReturn(new ConcurrentMapCache("test"));

        @SuppressWarnings("unchecked")
        ObjectProvider<CacheManager> cmP = mock(ObjectProvider.class);
        when(cmP.getIfAvailable()).thenReturn(cm);
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisTemplate<String, Object>> redisP = mock(ObjectProvider.class);
        when(redisP.getIfAvailable()).thenReturn(redis);
        @SuppressWarnings("unchecked")
        ObjectProvider<CacheBroadcast> bP = mock(ObjectProvider.class);
        when(bP.getIfAvailable()).thenReturn(broadcast);

        svc = new CacheService(cmP, redisP, bP);
    }

    @Test
    void getOrLoadCallsLoaderOnFirstMiss() {
        AtomicInteger calls = new AtomicInteger();
        String r = svc.getOrLoad("course:detail", "c-1", () -> {
            calls.incrementAndGet();
            return "v1";
        }, 300, true);
        assertEquals("v1", r);
        assertEquals(1, calls.get());
    }

    @Test
    void getOrLoadSecondCallHitsL1NoLoader() {
        AtomicInteger calls = new AtomicInteger();
        svc.getOrLoad("course:detail", "c-2", () -> {
            calls.incrementAndGet();
            return "v";
        }, 300, true);
        svc.getOrLoad("course:detail", "c-2", () -> {
            calls.incrementAndGet();
            return "v";
        }, 300, true);
        assertEquals(1, calls.get(), "第二次应命中 L1 不调 loader");
    }

    @Test
    void getOrLoadCachesEmptyWithSentinel() {
        AtomicInteger calls = new AtomicInteger();
        String r1 = svc.getOrLoad("u", "miss", () -> {
            calls.incrementAndGet();
            return null;
        }, 30, true);
        assertNull(r1);
        svc.getOrLoad("u", "miss", () -> {
            calls.incrementAndGet();
            return null;
        }, 30, true);
        assertEquals(1, calls.get(), "空结果应被哨兵缓存，loader 不再调");
    }

    @Test
    void getOrLoadEmptyNotCachedWhenDisabled() {
        AtomicInteger calls = new AtomicInteger();
        svc.getOrLoad("u", "miss2", () -> {
            calls.incrementAndGet();
            return null;
        }, 30, false);
        svc.getOrLoad("u", "miss2", () -> {
            calls.incrementAndGet();
            return null;
        }, 30, false);
        assertEquals(2, calls.get(), "cacheEmpty=false 时空不缓存");
    }

    @Test
    void evictRemovesFromL1AndBroadcasts() {
        svc.getOrLoad("course:detail", "c-3", () -> "v", 300, true);
        svc.evict("course:detail", "c-3");
        verify(broadcast).publishEvict("course:detail", "c-3");
    }

    // ---- L2 Redis integration paths (mocked, no real Redis) ----

    @Test
    void putWritesToL2AndL1() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> vops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vops);

        svc.put("course:detail", "k1", "val", 60);

        verify(vops).set("course:detail:k1", "val", 60L, TimeUnit.SECONDS);
    }

    @Test
    void getOrLoadPopulatesL2OnMiss() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> vops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vops);

        svc.getOrLoad("course:detail", "loaded", () -> "fresh", 120, true);

        verify(vops).set("course:detail:loaded", "fresh", 120L, TimeUnit.SECONDS);
    }

    @Test
    void getOrLoadCachesEmptySentinelInL2WhenRedisAvailable() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> vops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vops);

        String r = svc.getOrLoad("course:detail", "empty", () -> null, 30, true);

        assertNull(r);
        verify(vops).set(eq("course:detail:empty"), eq(CacheService.EMPTY_SENTINEL), eq(30L), eq(TimeUnit.SECONDS));
    }

    @Test
    void getOrLoadReturnsSentinelAsNullWhenL2HasIt() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> vops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vops);
        when(vops.get("course:detail:sent")).thenReturn(CacheService.EMPTY_SENTINEL);

        AtomicInteger calls = new AtomicInteger();
        String r = svc.getOrLoad("course:detail", "sent", () -> {
            calls.incrementAndGet();
            return "should-not-load";
        }, 30, true);

        assertNull(r);
        assertEquals(0, calls.get(), "L2 命中空哨兵应直接返回 null 不调 loader");
    }

    @Test
    void getOrLoadL2ExceptionDegradesToLoader() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> vops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vops);
        when(vops.get(anyString())).thenThrow(new RuntimeException("redis down"));

        String r = svc.getOrLoad("course:detail", "degrade", () -> "fallback", 30, true);

        assertEquals("fallback", r);
    }

    @Test
    void evictDeletesFromL2() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> vops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vops);

        svc.evict("course:detail", "del");

        verify(redis, atLeastOnce()).delete("course:detail:del");
    }

    @Test
    void evictToleratesRedisDeleteException() {
        when(redis.delete(anyString())).thenThrow(new RuntimeException("delete boom"));
        assertDoesNotThrow(() -> svc.evict("course:detail", "boom"));
    }

    @Test
    void putToleratesRedisSetException() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> vops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vops);
        doThrow(new RuntimeException("set boom"))
                .when(vops).set(anyString(), any(), anyLong(), any(TimeUnit.class));
        assertDoesNotThrow(() -> svc.put("course:detail", "k", "v", 30));
    }

    @Test
    void isEnabledReflectsCacheManagerAvailability() {
        assertTrue(svc.isEnabled());
    }
}
