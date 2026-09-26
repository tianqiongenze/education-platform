package com.ep.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Cache-hardening tests for the audit gaps: singleflight (击穿), TTL jitter
 * (雪崩), tenant-scoped keys (租户串号), SCAN-based evictAll (KEYS 阻塞) and
 * hit/miss stats (命中率可观测).
 */
class CacheServiceHardeningTest {

    private CacheService svc;
    private RedisTemplate<String, Object> redis;
    private CacheBroadcast broadcast;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        CacheManager cm = mock(CacheManager.class);
        redis = mock(RedisTemplate.class);
        broadcast = mock(CacheBroadcast.class);
        when(cm.getCache(anyString())).thenReturn(new ConcurrentMapCache("test"));

        ObjectProvider<CacheManager> cmP = mock(ObjectProvider.class);
        when(cmP.getIfAvailable()).thenReturn(cm);
        ObjectProvider<RedisTemplate<String, Object>> redisP = mock(ObjectProvider.class);
        when(redisP.getIfAvailable()).thenReturn(redis);
        ObjectProvider<CacheBroadcast> bP = mock(ObjectProvider.class);
        when(bP.getIfAvailable()).thenReturn(broadcast);

        svc = new CacheService(cmP, redisP, bP);
    }

    // ------------------------------------------------------------------
    // 1. Singleflight (cache breakdown / 击穿)
    // ------------------------------------------------------------------

    @Test
    void singleflightMergesConcurrentLoadsIntoOneLoaderCall() throws Exception {
        int threads = 8;
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loadCount = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> svc.getOrLoad("sf", "hot", () -> {
                    loadCount.incrementAndGet();
                    loaderStarted.countDown();
                    try {
                        assertTrue(releaseLoader.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "loaded";
                }, 60, false)));
            }
            // Wait until the loader is in flight, then let the waiters pile up.
            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS), "loader should have started");
            Thread.sleep(200); // let all remaining callers reach the in-flight future
            releaseLoader.countDown();

            for (Future<String> f : futures) {
                assertEquals("loaded", f.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, loadCount.get(),
                    "singleflight: N concurrent misses must trigger exactly ONE loader call");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void singleflightPropagatesLoaderExceptionToAllWaiters() throws Exception {
        int threads = 6;
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loadCount = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> svc.getOrLoad("sf", "boom", () -> {
                    loadCount.incrementAndGet();
                    loaderStarted.countDown();
                    try {
                        assertTrue(releaseLoader.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("boom");
                }, 60, false)));
            }
            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(200); // let all waiters pile onto the in-flight future
            releaseLoader.countDown();

            int failures = 0;
            for (Future<String> f : futures) {
                try {
                    f.get(5, TimeUnit.SECONDS);
                    fail("every waiter must observe the loader failure");
                } catch (java.util.concurrent.ExecutionException e) {
                    assertTrue(e.getCause() instanceof IllegalStateException
                                    && "boom".equals(e.getCause().getMessage()),
                            "waiters must receive the loader's original exception, got: " + e.getCause());
                    failures++;
                }
            }
            assertEquals(threads, failures, "every waiter must observe the failure");
            assertEquals(1, loadCount.get(), "the failing loader ran exactly once");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void singleflightCleansUpAfterCompletion() {
        // After a completed flight, the map is empty again and a NEW load runs.
        svc.getOrLoad("sf", "done", () -> "v1", 60, false);
        String second = svc.getOrLoad("sf", "done", () -> "v2", 60, false);
        assertEquals("v1", second, "second read is served from L1 (flight already completed+removed)");
    }

    // ------------------------------------------------------------------
    // 2. TTL jitter (avalanche / 雪崩)
    // ------------------------------------------------------------------

    @Test
    void jitteredTtlStaysWithinPointEightToOnePointTwo() {
        for (int i = 0; i < 500; i++) {
            long jittered = svc.jitteredTtl(100);
            assertTrue(jittered >= 80 && jittered <= 120,
                    "jittered TTL " + jittered + " must stay within [0.8x, 1.2x] of 100");
        }
    }

    @Test
    void jitteredTtlNeverDropsBelowOneSecond() {
        assertEquals(1, svc.jitteredTtl(1), "minimum TTL is 1s even with the smallest factor");
    }

    @Test
    void jitterSourceIsInjectableForDeterministicTests() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CacheManager> cmP = mock(ObjectProvider.class);
        when(cmP.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisTemplate<String, Object>> redisP = mock(ObjectProvider.class);
        when(redisP.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<CacheBroadcast> bP = mock(ObjectProvider.class);
        when(bP.getIfAvailable()).thenReturn(null);

        CacheService deterministic = new CacheService(cmP, redisP, bP, () -> 1.0);
        assertEquals(123, deterministic.jitteredTtl(123), "factor 1.0 keeps the TTL unchanged");

        CacheService doubleTtl = new CacheService(cmP, redisP, bP, () -> 1.2);
        assertEquals(240, doubleTtl.jitteredTtl(200));
    }

    @Test
    void jitteredTtlToleratesBrokenJitterSource() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CacheManager> cmP = mock(ObjectProvider.class);
        when(cmP.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisTemplate<String, Object>> redisP = mock(ObjectProvider.class);
        when(redisP.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<CacheBroadcast> bP = mock(ObjectProvider.class);
        when(bP.getIfAvailable()).thenReturn(null);

        CacheService broken = new CacheService(cmP, redisP, bP, () -> 1.0 / 0.0 * 0.0); // NaN
        assertEquals(60, broken.jitteredTtl(60), "non-finite factor falls back to no jitter");
    }

    // ------------------------------------------------------------------
    // 3. Tenant-scoped keys (P0 correctness / 租户串号)
    // ------------------------------------------------------------------

    @Test
    void tenantKeyPrefixesTenantAndDefaultsOnBlank() {
        assertEquals("t:acme:42", CacheService.tenantKey("acme", "42"));
        assertEquals("t:default:42", CacheService.tenantKey(null, "42"));
        assertEquals("t:default:42", CacheService.tenantKey("  ", "42"));
    }

    @Test
    void tenantScopedGetOrLoadIsolatesTenantsWithSameBusinessId() {
        AtomicInteger loads = new AtomicInteger();
        String a1 = svc.getOrLoad("course", "tenantA", "42", () -> "A:" + loads.incrementAndGet(), 300, false);
        String b1 = svc.getOrLoad("course", "tenantB", "42", () -> "B:" + loads.incrementAndGet(), 300, false);
        assertEquals("A:1", a1);
        assertEquals("B:2", b1);

        // Repeat reads: each tenant still sees its OWN cached entry.
        String a2 = svc.getOrLoad("course", "tenantA", "42", () -> "A:should-not-load", 300, false);
        String b2 = svc.getOrLoad("course", "tenantB", "42", () -> "B:should-not-load", 300, false);
        assertEquals("A:1", a2, "tenantA must not observe tenantB's cached value");
        assertEquals("B:2", b2, "tenantB must not observe tenantA's cached value");
        assertEquals(2, loads.get(), "each tenant loaded exactly once");
    }

    @Test
    void tenantScopedKeysAreWrittenDistinctlyToL2() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> vops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vops);

        svc.getOrLoad("course", "tenantA", "7", () -> "va", 100, false);
        svc.getOrLoad("course", "tenantB", "7", () -> "vb", 100, false);

        verify(vops).set(eq("course:t:tenantA:7"), eq("va"), anyLong(), eq(TimeUnit.SECONDS));
        verify(vops).set(eq("course:t:tenantB:7"), eq("vb"), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void tenantScopedEvictOnlyRemovesThatTenantsEntry() {
        svc.getOrLoad("course", "tenantA", "9", () -> "A", 300, false);
        svc.getOrLoad("course", "tenantB", "9", () -> "B", 300, false);

        svc.evict("course", "tenantA", "9");

        // tenantB's entry survives the eviction of tenantA's entry.
        String b = svc.getOrLoad("course", "tenantB", "9", () -> "B-reload", 300, false);
        assertEquals("B", b);
        // tenantA must reload (its entry was evicted).
        String a = svc.getOrLoad("course", "tenantA", "9", () -> "A-reload", 300, false);
        assertEquals("A-reload", a);
        verify(broadcast).publishEvict("course", "t:tenantA:9");
    }

    // ------------------------------------------------------------------
    // 4. SCAN-based evictAll (KEYS 阻塞)
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void evictAllUsesScanCursorInsteadOfKeys() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("course:t:a:p1:10", "course:t:b:p2:10");
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);

        svc.evictAll("course");

        verify(redis, never()).keys(anyString());
        verify(redis).scan(any(ScanOptions.class));
        verify(redis).delete(List.of("course:t:a:p1:10", "course:t:b:p2:10"));
        verify(broadcast).publishEvictAll("course");
    }

    @Test
    @SuppressWarnings("unchecked")
    void evictAllDeletesInBatchesOfFiveHundred() {
        int total = CacheService.EVICTALL_SCAN_BATCH + 1;
        // Build a cursor mock over `total` keys.
        Cursor<String> cursor = mock(Cursor.class);
        Boolean[] hasNextAnswers = new Boolean[total + 1];
        java.util.Arrays.fill(hasNextAnswers, true);
        hasNextAnswers[total] = false;
        when(cursor.hasNext()).thenReturn(true,
                java.util.Arrays.copyOfRange(hasNextAnswers, 1, hasNextAnswers.length));
        String[] keys = new String[total];
        for (int i = 0; i < total; i++) {
            keys[i] = "course:k" + i;
        }
        when(cursor.next()).thenReturn(keys[0], java.util.Arrays.copyOfRange(keys, 1, keys.length));
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);

        svc.evictAll("course");

        // 501 keys -> one full batch of 500 + one remainder batch of 1.
        verify(redis, times(2)).delete(anyCollection());
        verify(redis, never()).keys(anyString());
    }

    @Test
    void evictAllToleratesScanFailure() {
        when(redis.scan(any(ScanOptions.class))).thenThrow(new RuntimeException("scan down"));
        assertDoesNotThrow(() -> svc.evictAll("course"));
        verify(broadcast).publishEvictAll("course");
    }

    // ------------------------------------------------------------------
    // 5. Stats observability (命中率可观测)
    // ------------------------------------------------------------------

    @Test
    void statsCountsL1AndL2HitsAndMisses() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> vops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vops);
        // L2 misses by default (no stubbed value).
        when(vops.get(anyString())).thenReturn(null);

        svc.getOrLoad("s", "k1", () -> "v1", 60, true); // L1 miss + L2 miss + load
        svc.getOrLoad("s", "k1", () -> "v1", 60, true); // L1 hit
        when(vops.get("s:k2")).thenReturn("from-redis");
        svc.getOrLoad("s", "k2", () -> "never", 60, true); // L1 miss + L2 hit

        CacheService.CacheStats st = svc.getStats();
        assertEquals(1, st.l1Hits());
        assertEquals(2, st.l1Misses());
        assertEquals(1, st.l2Hits());
        assertEquals(1, st.l2Misses()); // only k1's first call fell through to L2-miss
        assertEquals(1, st.loaderInvocations());
        assertEquals(0, st.loaderErrors());
        assertEquals(1.0 / 3.0, st.l1HitRate(), 1e-9, "1 L1 hit out of 3 L1 lookups");
        assertEquals(0.5, st.l2HitRate(), 1e-9, "1 L2 hit out of 2 L2 lookups");
    }

    @Test
    void statsCountsLoaderErrors() {
        try {
            svc.getOrLoad("s", "err", () -> {
                throw new IllegalArgumentException("nope");
            }, 60, false);
            fail("loader exception must propagate");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        CacheService.CacheStats st = svc.getStats();
        assertEquals(1, st.loaderInvocations());
        assertEquals(1, st.loaderErrors());
    }

    @Test
    void l2HitPromotesValueIntoL1() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> vops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vops);
        when(vops.get("s:promo")).thenReturn("redis-value");
        AtomicInteger loads = new AtomicInteger();

        assertEquals("redis-value", svc.getOrLoad("s", "promo", () -> "x" + loads.incrementAndGet(), 60, true));
        // Second read must hit L1 (redis only touched once).
        assertEquals("redis-value", svc.getOrLoad("s", "promo", () -> "x" + loads.incrementAndGet(), 60, true));
        verify(vops, times(1)).get("s:promo");
        assertEquals(0, loads.get());
    }
}
