package com.ep.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * OSW observability iron rule: cache-hit info must reach the caller's
 * response, not stay in aggregate counters alone. Verifies that
 * {@link CacheService#getOrLoad} records a per-thread, per-call hit context
 * ({@link CacheCallContext}) for L1 hits, L2 hits and misses, and that the
 * context is explicit-clear only (no implicit clearing between calls).
 */
class CacheCallContextTest {

    private CacheService svc;
    private RedisTemplate<String, Object> redis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        CacheManager cm = mock(CacheManager.class);
        when(cm.getCache(anyString())).thenReturn(new ConcurrentMapCache("test"));
        redis = mock(RedisTemplate.class);

        ObjectProvider<CacheManager> cmP = mock(ObjectProvider.class);
        when(cmP.getIfAvailable()).thenReturn(cm);
        ObjectProvider<RedisTemplate<String, Object>> redisP = mock(ObjectProvider.class);
        when(redisP.getIfAvailable()).thenReturn(redis);
        ObjectProvider<CacheBroadcast> bP = mock(ObjectProvider.class);
        when(bP.getIfAvailable()).thenReturn(null);

        svc = new CacheService(cmP, redisP, bP);
        CacheCallContext.clear();
    }

    @AfterEach
    void cleanup() {
        CacheCallContext.clear();
    }

    @Test
    @DisplayName("snapshot is absent before any cache op")
    void snapshotAbsentBeforeAnyCall() {
        assertTrue(CacheCallContext.snapshot().isEmpty());
    }

    @Test
    @DisplayName("L1 hit: level=L1, key recorded, latency >= 0, hit()=true")
    void l1HitRecorded() {
        svc.getOrLoad("c", "k1", () -> "v", 60, true); // miss → loader runs
        String r = svc.getOrLoad("c", "k1",
                () -> fail("loader must not run on L1 hit"), 60, true);
        assertEquals("v", r);

        Optional<CacheCallContext.Hit> snap = CacheCallContext.snapshot();
        assertTrue(snap.isPresent(), "L1 hit must be recorded in the per-call context");
        assertEquals(CacheCallContext.Level.L1, snap.get().level());
        assertEquals("k1", snap.get().key());
        assertTrue(snap.get().latencyMicros() >= 0);
        assertTrue(snap.get().hit());
    }

    @Test
    @DisplayName("L2 hit: level=L2, key recorded, loader skipped")
    void l2HitRecorded() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> vops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vops);
        when(vops.get("c:k2")).thenReturn("l2value");

        String r = svc.getOrLoad("c", "k2", () -> "loaded", 60, true);
        assertEquals("l2value", r);

        Optional<CacheCallContext.Hit> snap = CacheCallContext.snapshot();
        assertTrue(snap.isPresent(), "L2 hit must be recorded in the per-call context");
        assertEquals(CacheCallContext.Level.L2, snap.get().level());
        assertEquals("k2", snap.get().key());
        assertTrue(snap.get().hit());
    }

    @Test
    @DisplayName("miss (loader runs): level=NONE, hit()=false")
    void missRecordedAsNone() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> vops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vops);
        when(vops.get(anyString())).thenReturn(null);

        String r = svc.getOrLoad("c", "k3", () -> "fresh", 60, true);
        assertEquals("fresh", r);

        Optional<CacheCallContext.Hit> snap = CacheCallContext.snapshot();
        assertTrue(snap.isPresent(), "even a miss must leave a NONE snapshot");
        assertEquals(CacheCallContext.Level.NONE, snap.get().level());
        assertFalse(snap.get().hit());
    }

    @Test
    @DisplayName("tenant-scoped overload records the effective (tenant-prefixed) key")
    void tenantVariantRecordsEffectiveKey() {
        svc.getOrLoad("c", "tenant-1", "k4", () -> "v", 60, true);
        Optional<CacheCallContext.Hit> snap = CacheCallContext.snapshot();
        assertTrue(snap.isPresent());
        assertEquals("t:tenant-1:k4", snap.get().key());
    }

    @Test
    @DisplayName("clear() removes the context; snapshot stays absent until the next op")
    void clearRemovesContext() {
        svc.getOrLoad("c", "k5", () -> "v", 60, true);
        assertTrue(CacheCallContext.snapshot().isPresent());

        CacheCallContext.clear();
        assertTrue(CacheCallContext.snapshot().isEmpty(),
                "clear() must remove the per-thread context (no leak across requests)");
    }

    @Test
    @DisplayName("context is NOT auto-cleared between ops — callers own the clearing")
    void contextPersistsAcrossCallsUntilCleared() {
        svc.getOrLoad("c", "k6", () -> "v", 60, true);
        svc.getOrLoad("c", "k6-again", () -> "v", 60, true);
        // last op (k6-again miss) overwrites the context; it must still be present
        assertEquals("k6-again", CacheCallContext.snapshot().orElseThrow().key());
    }
}
