package com.ep.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Observability tests: Micrometer counter export ({@link CacheMetrics}) and
 * the periodic stats logger ({@link CacheStatsLogger}).
 */
class CacheObservabilityTest {

    private CacheService svc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        CacheManager cm = mock(CacheManager.class);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        when(cm.getCache(anyString())).thenReturn(new ConcurrentMapCache("test"));

        ObjectProvider<CacheManager> cmP = mock(ObjectProvider.class);
        when(cmP.getIfAvailable()).thenReturn(cm);
        ObjectProvider<RedisTemplate<String, Object>> redisP = mock(ObjectProvider.class);
        when(redisP.getIfAvailable()).thenReturn(redis);
        ObjectProvider<CacheBroadcast> bP = mock(ObjectProvider.class);
        when(bP.getIfAvailable()).thenReturn(null);

        svc = new CacheService(cmP, redisP, bP);
    }

    @Test
    void cacheMetricsExportsAllFourCounters() {
        svc.getOrLoad("s", "k", () -> "v", 60, true); // L1 miss + L2 miss + load
        svc.getOrLoad("s", "k", () -> "v", 60, true); // L1 hit

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CacheMetrics(svc).bindTo(registry);

        assertEquals(1.0, registry.get(CacheMetrics.L1_HIT).functionCounter().count());
        assertEquals(1.0, registry.get(CacheMetrics.L1_MISS).functionCounter().count());
        assertEquals(0.0, registry.get(CacheMetrics.L2_HIT).functionCounter().count());
        assertEquals(1.0, registry.get(CacheMetrics.L2_MISS).functionCounter().count());
    }

    @Test
    void cacheMetricsReflectsLaterActivity() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CacheMetrics(svc).bindTo(registry);

        svc.getOrLoad("s", "k", () -> "v", 60, true);

        assertEquals(1.0, registry.get(CacheMetrics.L1_MISS).functionCounter().count(),
                "function counters read the live AtomicLong");
    }

    @Test
    void statsLoggerDisabledForNonPositiveIntervalOrNullService() {
        assertFalse(new CacheStatsLogger(svc, 0).isEnabled());
        assertFalse(new CacheStatsLogger(svc, -5).isEnabled());
        assertFalse(new CacheStatsLogger(null, 60).isEnabled());
    }

    @Test
    void statsLoggerStartsDaemonAndClosesCleanly() {
        CacheStatsLogger logger = new CacheStatsLogger(svc, 3600);
        assertTrue(logger.isEnabled());
        assertDoesNotThrow(logger::close);
        // A closed logger must not accept... nothing to assert beyond no-throw.
    }
}
