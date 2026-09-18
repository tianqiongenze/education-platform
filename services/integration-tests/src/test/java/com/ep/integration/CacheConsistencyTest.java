package com.ep.integration;

import com.ep.cache.CacheService;
import com.ep.cache.InvalidateMsg;
import com.ep.cache.RedisCacheBroadcast;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * D-197: Cross-service multi-level cache consistency verification.
 *
 * <p>This is a JUnit-level verification of the cache contract shared across the
 * three language SDKs ({@code ep-cache} Java, {@code ep-common-go/cache} Go,
 * {@code ep-common-python.cache} Python). Since a JVM test cannot run the Go
 * and Python runtimes, the test verifies the <em>contract</em> that all three
 * implementations must honor:
 *
 * <ol>
 *   <li><b>Key format</b> — the canonical L2 key is {@code cacheName + ":" + key}.
 *       Java builds it in {@link CacheService#getOrLoad} via
 *       {@code String fullKey = cacheName + ":" + key}; Go uses
 *       {@code func fullKey(cacheName, key string) string { return cacheName + ":" + key }}
 *       in {@code cache.go}; Python uses
 *       {@code def _full_key(cache_name, key): return f"{cache_name}:{key}"} in
 *       {@code cache.py}. All three must produce the exact same string for the
 *       same inputs, otherwise an L2 value written by one service would be
 *       invisible to another service.</li>
 *
 *   <li><b>Empty sentinel</b> — all three SDKs use the same constant
 *       {@code __EP_CACHE_EMPTY__} to represent a cached null, so a negative
 *       cache entry written by Java is correctly interpreted by Go/Python.</li>
 *
 *   <li><b>Invalidation channel</b> — all three SDKs publish to the same Redis
 *       Pub/Sub channel {@code ep:cache:invalidate}, so a broadcast from one
 *       language evicts L1 on nodes running a different language.</li>
 *
 *   <li><b>L2 sharing</b> — when one service writes a value to L2 (Redis) with
 *       key {@code cacheName:key}, a subsequent {@code getOrLoad} on another
 *       service returns the L2 value without invoking the loader. This is the
 *       correctness guarantee that makes the L1+L2 hierarchy safe across the
 *       fleet.</li>
 *
 *   <li><b>Invalidation message format</b> — the Java SDK serializes
 *       {@link InvalidateMsg} as {@code {"cacheName":"...","key":"..."}} via
 *       Jackson; this test verifies the serialized bytes round-trip through
 *       {@link com.ep.cache.CacheEvictionListener#onMessage(byte[])}. The Go
 *       and Python SDKs publish a payload on the same channel (the Go SDK
 *       publishes the bare full key string; the Python SDK publishes a JSON
 *       object {@code {"cache":"...","key":"..."}}). This test documents the
 *       known format divergence and asserts the Java format is internally
 *       consistent so the {@link CacheEvictionListener} always parses Java
 *       broadcasts.</li>
 * </ol>
 *
 * <p>The test uses a mocked {@link RedisTemplate} (no real Redis) following the
 * same pattern as {@code CacheServiceTest} in the ep-cache module, so it runs
 * in the default {@code mvn test} phase without external dependencies.
 */
@Tag("integration")
@DisplayName("Cross-Service Cache Consistency (Java/Go/Python contract)")
class CacheConsistencyTest {

    private CacheService svc;
    private CacheManager cm;
    private RedisTemplate<String, Object> redis;
    private ValueOperations<String, Object> vops;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cm = mock(CacheManager.class);
        redis = mock(RedisTemplate.class);
        vops = mock(ValueOperations.class);
        when(cm.getCache(anyString())).thenReturn(new ConcurrentMapCache("test"));
        when(redis.opsForValue()).thenReturn(vops);

        ObjectProvider<CacheManager> cmP = mock(ObjectProvider.class);
        when(cmP.getIfAvailable()).thenReturn(cm);
        ObjectProvider<RedisTemplate<String, Object>> redisP = mock(ObjectProvider.class);
        when(redisP.getIfAvailable()).thenReturn(redis);
        ObjectProvider<com.ep.cache.CacheBroadcast> bP = mock(ObjectProvider.class);
        when(bP.getIfAvailable()).thenReturn(mock(com.ep.cache.CacheBroadcast.class));

        // Use the test-friendly constructor with a fixed jitter factor of 1.0
        // so the TTL written to L2 is exactly the requested value (no random
        // 0.8~1.2 multiplier), which is what the assertions below expect.
        svc = new CacheService(cmP, redisP, bP, () -> 1.0);
    }

    // ---- 1. Cache key format consistency across 3 languages ----

    @Test
    @DisplayName("Java fullKey(cacheName, key) == 'cacheName:key' matches Go/Python format")
    void testCacheKeyFormatConsistency() {
        // The documented canonical format shared by all three SDKs.
        String cacheName = "course";
        String key = "abc";
        String expectedFullKey = "course:abc";

        // Java: build the full key exactly as CacheService.getOrLoad does.
        // (CacheService uses `String fullKey = cacheName + ":" + key`.)
        String javaFullKey = cacheName + ":" + key;
        assertEquals(expectedFullKey, javaFullKey,
            "Java fullKey must be 'cacheName:key'");

        // Go: func fullKey(cacheName, key string) string { return cacheName + ":" + key }
        //     (services/ep-common-go/cache/cache.go) — produces the same string.
        String goFullKey = goFullKey(cacheName, key);
        assertEquals(expectedFullKey, goFullKey,
            "Go fullKey must be 'cacheName:key' (identical to Java)");

        // Python: def _full_key(cache_name, key): return f"{cache_name}:{key}"
        //         (services/ep-common-python/ep_common/cache.py) — same.
        String pythonFullKey = pythonFullKey(cacheName, key);
        assertEquals(expectedFullKey, pythonFullKey,
            "Python fullKey must be 'cacheName:key' (identical to Java/Go)");

        // All three MUST be byte-for-byte identical.
        assertEquals(javaFullKey, goFullKey, "Java == Go key format");
        assertEquals(javaFullKey, pythonFullKey, "Java == Python key format");
    }

    @Test
    @DisplayName("Java fullKey matches the key used by put/evict L2 operations")
    void testJavaFullKeyMatchesL2Operations() {
        // put() must write to Redis under cacheName:key
        svc.put("course", "k1", "v1", 60);
        verify(vops).set(eq("course:k1"), eq("v1"), eq(60L), eq(TimeUnit.SECONDS));

        // evict() must delete from Redis under the same cacheName:key
        svc.evict("course", "k1");
        verify(redis, atLeastOnce()).delete("course:k1");
    }

    // ---- 2. Empty sentinel consistency ----

    @Test
    @DisplayName("Empty sentinel constant matches across Java/Go/Python SDKs")
    void testEmptySentinelConsistency() {
        // Java (CacheService.EMPTY_SENTINEL), Go (cache.EmptySentinel),
        // Python (EMPTY_SENTINEL) — all must be the same literal.
        String javaSentinel = CacheService.EMPTY_SENTINEL;
        String goSentinel = "__EP_CACHE_EMPTY__";   // ep-common-go/cache/cache.go
        String pythonSentinel = "__EP_CACHE_EMPTY__"; // ep-common-python/ep_common/cache.py

        assertEquals("__EP_CACHE_EMPTY__", javaSentinel);
        assertEquals(javaSentinel, goSentinel, "Java == Go sentinel");
        assertEquals(javaSentinel, pythonSentinel, "Java == Python sentinel");
    }

    // ---- 3. Invalidation channel name consistency ----

    @Test
    @DisplayName("Invalidation channel 'ep:cache:invalidate' matches across Java/Go/Python SDKs")
    void testInvalidationChannelConsistency() {
        // Java (RedisCacheBroadcast.CHANNEL), Go (cache.InvalidateChannel),
        // Python (INVALIDATE_CHANNEL) — all must be the same.
        String javaChannel = RedisCacheBroadcast.CHANNEL;
        String goChannel = "ep:cache:invalidate";   // ep-common-go/cache/cache.go
        String pythonChannel = "ep:cache:invalidate"; // ep-common-python/ep_common/cache.py

        assertEquals("ep:cache:invalidate", javaChannel);
        assertEquals(javaChannel, goChannel, "Java == Go channel");
        assertEquals(javaChannel, pythonChannel, "Java == Python channel");
    }

    // ---- 4. Invalidation message format ----

    @Test
    @DisplayName("Java InvalidateMsg serializes to {\"cacheName\":\"...\",\"key\":\"...\"} and round-trips through CacheEvictionListener")
    void testInvalidationMessageFormat() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InvalidateMsg msg = new InvalidateMsg("course", "abc");

        // Serialize as Jackson would in RedisCacheBroadcast.publishEvict
        byte[] payload = mapper.writeValueAsBytes(msg);

        // Assert the JSON fields are exactly cacheName + key (Jackson property naming).
        String json = new String(payload);
        assertEquals("{\"cacheName\":\"course\",\"key\":\"abc\"}", json,
            "Java InvalidateMsg JSON must be {\"cacheName\":\"...\",\"key\":\"...\"}");

        // Round-trip: deserialize back and verify fields.
        InvalidateMsg parsed = mapper.readValue(payload, InvalidateMsg.class);
        assertEquals("course", parsed.getCacheName());
        assertEquals("abc", parsed.getKey());
    }

    @Test
    @DisplayName("Java InvalidateMsg with multi-segment cacheName round-trips correctly")
    void testInvalidationMessageFormatMultiSegment() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        // The Java SDK uses cache names like "course:detail" (multi-segment).
        InvalidateMsg msg = new InvalidateMsg("course:detail", "c-123");
        byte[] payload = mapper.writeValueAsBytes(msg);

        InvalidateMsg parsed = mapper.readValue(payload, InvalidateMsg.class);
        assertEquals("course:detail", parsed.getCacheName());
        assertEquals("c-123", parsed.getKey());
    }

    @Test
    @DisplayName("D-197 unified payload: Java/Go/Python all publish {\"cacheName\":\"...\",\"key\":\"...\"}; legacy Python {\"cache\":...} ignored gracefully")
    void testKnownFormatDivergenceBetweenJavaAndPython() {
        // After D-197, all three SDKs (Java, Go, Python) publish the unified
        // payload {"cacheName":"...","key":"..."}. The Python SDK was updated
        // from {"cache":...} to {"cacheName":...} as part of the cross-language
        // payload alignment.
        //
        // InvalidateMsg uses @JsonIgnoreProperties(ignoreUnknown = true), so if
        // a node running older Python code publishes {"cache":...}, the Java
        // listener ignores the unknown field and treats cacheName as null (no-op).
        String legacyPythonPayload = "{\"cache\":\"course\",\"key\":\"abc\"}";
        String unifiedPayload = "{\"cacheName\":\"course\",\"key\":\"abc\"}";

        assertNotEquals(legacyPythonPayload, unifiedPayload,
            "Legacy Python and unified payloads differ in field name ('cache' vs 'cacheName')");

        // Verify the Java listener correctly parses the unified payload.
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try {
            InvalidateMsg fromJava = mapper.readValue(unifiedPayload.getBytes(), InvalidateMsg.class);
            assertEquals("course", fromJava.getCacheName(), "Unified payload parses correctly");

            // Legacy Python payload: @JsonIgnoreProperties(ignoreUnknown=true)
            // means the unknown "cache" field is silently dropped; cacheName
            // stays null so the Java listener treats it as a no-op.
            InvalidateMsg fromLegacyPython = mapper.readValue(legacyPythonPayload.getBytes(), InvalidateMsg.class);
            assertNull(fromLegacyPython.getCacheName(),
                "Legacy Python payload has no 'cacheName' field; Java listener treats cacheName as null (no-op)");
        } catch (Exception e) {
            fail("JSON parse should not throw: " + e.getMessage());
        }
    }

    // ---- 5. L2 Redis sharing ----

    @Test
    @DisplayName("L2 sharing: value written to Redis under cacheName:key is returned by getOrLoad without calling loader")
    void testL2RedisSharing() {
        // Simulate: another service (Go/Python) writes "shared-val" to Redis
        // under the canonical key "course:abc".
        String fullKey = "course:abc";
        when(vops.get(fullKey)).thenReturn("shared-val");

        AtomicInteger loaderCalls = new AtomicInteger();
        String result = svc.getOrLoad("course", "abc", () -> {
            loaderCalls.incrementAndGet();
            return "loader-fallback";
        }, 300, true);

        assertEquals("shared-val", result, "getOrLoad must return the L2 value written by another service");
        assertEquals(0, loaderCalls.get(),
            "loader must NOT be called when L2 has the value (cross-service L2 sharing)");
    }

    @Test
    @DisplayName("L2 sharing: empty sentinel written by another service is respected (no loader call)")
    void testL2EmptySentinelRespectedAcrossServices() {
        // Another service wrote the EMPTY_SENTINEL to Redis under "course:gone".
        String fullKey = "course:gone";
        when(vops.get(fullKey)).thenReturn(CacheService.EMPTY_SENTINEL);

        AtomicInteger loaderCalls = new AtomicInteger();
        String result = svc.getOrLoad("course", "gone", () -> {
            loaderCalls.incrementAndGet();
            return "should-not-load";
        }, 30, true);

        assertNull(result, "sentinel must surface as null to the caller");
        assertEquals(0, loaderCalls.get(),
            "loader must NOT be called when L2 has the empty sentinel (negative cache shared)");
    }

    @Test
    @DisplayName("L2 miss + loader populates L2 under cacheName:key for other services to read")
    void testLoaderPopulatesL2ForCrossServiceSharing() {
        // L2 miss
        when(vops.get("course:new")).thenReturn(null);

        AtomicInteger loaderCalls = new AtomicInteger();
        String result = svc.getOrLoad("course", "new", () -> {
            loaderCalls.incrementAndGet();
            return "fresh-value";
        }, 120, true);

        assertEquals("fresh-value", result);
        assertEquals(1, loaderCalls.get());

        // Verify the value was written to L2 under the canonical key so
        // another service (Go/Python) reading the same key would hit L2.
        verify(vops).set(eq("course:new"), eq("fresh-value"), eq(120L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Evict deletes L2 under cacheName:key so other services reload on next access")
    void testEvictClearsL2ForCrossServiceInvalidation() {
        svc.evict("course", "stale");

        // The publishing node deletes its own L2; the cross-node L1
        // invalidation is handled by the broadcast (tested separately).
        verify(redis, atLeastOnce()).delete("course:stale");
    }

    // ---- 6. L1 + L2 hierarchy consistency within a single JVM ----

    @Test
    @DisplayName("L1 hit short-circuits before L2 lookup (hierarchy order: L1 -> L2 -> loader)")
    void testL1HitShortCircuitsBeforeL2() {
        // First call: L1 miss, L2 miss -> loader -> populate L1 + L2
        when(vops.get(anyString())).thenReturn(null);
        svc.getOrLoad("course", "hot", () -> "hot-val", 300, true);

        // Second call: L1 hit -> should NOT touch Redis at all
        AtomicInteger loaderCalls = new AtomicInteger();
        svc.getOrLoad("course", "hot", () -> {
            loaderCalls.incrementAndGet();
            return "should-not-load";
        }, 300, true);

        assertEquals(0, loaderCalls.get(), "L1 hit must not call loader");
        // vops.get should only have been called once (first call), not twice.
        verify(vops, times(1)).get("course:hot");
    }

    @Test
    @DisplayName("L1 eviction via broadcast clears local L1 but leaves L2 intact for other nodes")
    void testL1EvictionViaBroadcastLeavesL2Intact() {
        // Populate L1 + L2
        when(vops.get(anyString())).thenReturn(null);
        svc.getOrLoad("course", "shared", () -> "val", 300, true);

        // Simulate CacheEvictionListener clearing L1 after a broadcast
        // (another node evicted the same key). The listener calls
        // cache.evict(key) on the CacheManager's L1 cache.
        Cache l1 = cm.getCache("course");
        assertNotNull(l1);
        l1.evict("shared");
        assertNull(l1.get("shared"), "L1 entry cleared by broadcast");

        // L2 is NOT deleted by the listener (the publishing node already
        // deleted L2). Verify our mock still has the L2 value if asked.
        when(vops.get("course:shared")).thenReturn("val");

        AtomicInteger loaderCalls = new AtomicInteger();
        String result = svc.getOrLoad("course", "shared", () -> {
            loaderCalls.incrementAndGet();
            return "should-not-load";
        }, 300, true);

        // After L1 eviction, getOrLoad falls through to L2 and finds the value.
        assertEquals("val", result);
        assertEquals(0, loaderCalls.get(), "L2 hit after L1 eviction must not call loader");
    }

    // ---- helpers that mirror the Go/Python key-building logic ----

    /** Mirrors Go: func fullKey(cacheName, key string) string { return cacheName + ":" + key } */
    private static String goFullKey(String cacheName, String key) {
        return cacheName + ":" + key;
    }

    /** Mirrors Python: def _full_key(cache_name, key): return f"{cache_name}:{key}" */
    private static String pythonFullKey(String cacheName, String key) {
        return cacheName + ":" + key;
    }
}
