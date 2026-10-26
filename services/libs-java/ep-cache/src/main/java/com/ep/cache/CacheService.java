package com.ep.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Two-level (L1 Caffeine + L2 Redis) read-through cache with cross-node L1
 * invalidation via {@link CacheBroadcast}.
 *
 * <p>Cache-avalanche/stampede hardening (audit P0/P1 round):
 * <ul>
 *   <li><b>Singleflight (cache stampede / breakdown)</b>: concurrent
 *       {@link #getOrLoad} calls for the same key share one in-flight
 *       {@code CompletableFuture}; the first caller runs the loader, all other
 *       waiters join its result (or its exception). The future is removed from
 *       the in-flight map as soon as the load finishes.</li>
 *   <li><b>TTL jitter (avalanche)</b>: every write to L2 applies a random
 *       {@code 0.8 ~ 1.2} factor to the requested TTL so masses of keys do not
 *       expire simultaneously. The factor source is injectable for tests.</li>
 *   <li><b>Tenant-scoped keys (P0 correctness)</b>: the
 *       {@code (cacheName, tenantId, key)} overloads build keys via
 *       {@link #tenantKey(String, String)} so two tenants reading the same
 *       business id can never observe each other's cached value.</li>
 *   <li><b>SCAN-based evictAll</b>: bulk invalidation uses a Redis SCAN cursor
 *       instead of the blocking KEYS command.</li>
 *   <li><b>Observability</b>: L1/L2 hit-miss counters are exposed through
 *       {@link #getStats()} (and optionally exported to Micrometer by
 *       {@code CacheConfig}).</li>
 * </ul>
 *
 * <p>Note: loaders must not recursively call {@code getOrLoad} for the same
 * key — the singleflight guard would make the loader wait on its own future.
 */
@Component
public class CacheService {
    private static final Logger log = LoggerFactory.getLogger(CacheService.class);
    public static final String EMPTY_SENTINEL = "__EP_CACHE_EMPTY__";

    /** In-flight loader futures for singleflight (stampede protection). */
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlightLoads = new ConcurrentHashMap<>();

    /** v7 (D-233): hotspot key detector — closes the detect leg of the hit-rate loop. */
    private final HotspotDetector hotspotDetector = new HotspotDetector();

    /** Default TTL jitter: random factor in [0.8, 1.2]. */
    private static final DoubleSupplier DEFAULT_JITTER = () -> 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;

    private final ObjectProvider<CacheManager> cmProvider;
    private final ObjectProvider<RedisTemplate<String, Object>> redisProvider;
    private final ObjectProvider<CacheBroadcast> broadcastProvider;
    private final DoubleSupplier ttlJitterRatio;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // ---- hit/miss counters (observability, audit: "命中率不可观测") ----
    private final AtomicLong l1Hits = new AtomicLong();
    private final AtomicLong l1Misses = new AtomicLong();
    private final AtomicLong l2Hits = new AtomicLong();
    private final AtomicLong l2Misses = new AtomicLong();
    private final AtomicLong loaderInvocations = new AtomicLong();
    private final AtomicLong loaderErrors = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public CacheService(ObjectProvider<CacheManager> cm,
                        ObjectProvider<RedisTemplate<String, Object>> redis,
                        ObjectProvider<CacheBroadcast> b) {
        this(cm, redis, b, DEFAULT_JITTER);
    }

    /** Test-friendly constructor: injects a deterministic TTL jitter factor. */
    public CacheService(ObjectProvider<CacheManager> cm,
                 ObjectProvider<RedisTemplate<String, Object>> redis,
                 ObjectProvider<CacheBroadcast> b,
                 DoubleSupplier ttlJitterRatio) {
        this.cmProvider = cm;
        this.redisProvider = redis;
        this.broadcastProvider = b;
        this.ttlJitterRatio = ttlJitterRatio == null ? DEFAULT_JITTER : ttlJitterRatio;
    }

    public boolean isEnabled() {
        CacheManager cm = cmProvider.getIfAvailable();
        return cm != null;
    }

    /**
     * Builds the tenant-scoped cache key: {@code t:<tenantId>:<key>}.
     * A blank tenant id falls back to {@code default} so legacy callers that
     * have no tenant context still get a stable, non-colliding namespace.
     */
    public static String tenantKey(String tenantId, String key) {
        String tenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        return "t:" + tenant + ":" + key;
    }

    // ------------------------------------------------------------------
    // getOrLoad
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String cacheName, String key, Supplier<T> loader, long ttlSec, boolean cacheEmpty) {
        return getOrLoadInternal(cacheName, key, loader, ttlSec, cacheEmpty);
    }

    /**
     * Tenant-scoped variant (P0: 租户未进 key). The effective key is
     * {@link #tenantKey(String, String) tenantKey(tenantId, key)}, so two
     * tenants referencing the same business id get isolated cache entries in
     * both L1 and L2.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String cacheName, String tenantId, String key, Supplier<T> loader, long ttlSec, boolean cacheEmpty) {
        return getOrLoadInternal(cacheName, tenantKey(tenantId, key), loader, ttlSec, cacheEmpty);
    }

    @SuppressWarnings("unchecked")
    private <T> T getOrLoadInternal(String cacheName, String effKey, Supplier<T> loader, long ttlSec, boolean cacheEmpty) {
        // L1 Caffeine (via Spring CacheManager)
        CacheManager cm = cmProvider.getIfAvailable();
        Cache cache = cm == null ? null : cm.getCache(cacheName);
        if (cache != null) {
            Cache.ValueWrapper w = cache.get(effKey);
            if (w != null) {
                l1Hits.incrementAndGet();
                return unwrap((T) w.get());
            }
        }
        l1Misses.incrementAndGet();
        // L2 Redis
        String fullKey = cacheName + ":" + effKey;
        RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                Object raw = redis.opsForValue().get(fullKey);
                if (raw != null) {
                    l2Hits.incrementAndGet();
                    // promote to L1 so the next call is a local hit
                    if (cache != null) {
                        cache.put(effKey, raw);
                    }
                    if (EMPTY_SENTINEL.equals(raw)) return null;
                    return (T) raw;
                }
                l2Misses.incrementAndGet();
                // v7 (D-233): a miss that must fall through to the loader feeds
                // the hotspot detector — repeated fall-throughs on one key are
                // the actionable signal for L1 sizing / TTL tuning.
                hotspotDetector.recordFallThrough(cacheName, effKey);
            } catch (Exception e) {
                l2Misses.incrementAndGet();
                log.warn("L2 redis miss/degrade: {}", e.getMessage());
            }
        }
        return loadWithSingleflight(cacheName, effKey, fullKey, loader, ttlSec, cacheEmpty);
    }

    /**
     * Singleflight loader execution: only ONE caller per (cache,key) runs the
     * loader; concurrent callers join the same future and receive the same
     * value — or the same exception (propagated to every waiter).
     */
    @SuppressWarnings("unchecked")
    private <T> T loadWithSingleflight(String cacheName, String effKey, String fullKey,
                                       Supplier<T> loader, long ttlSec, boolean cacheEmpty) {
        String flightKey = fullKey;
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlightLoads.putIfAbsent(flightKey, mine);
        if (existing != null) {
            // Another thread is loading this exact key — wait for its result.
            try {
                return (T) existing.join();
            } catch (CompletionException ce) {
                throw propagate(ce.getCause() != null ? ce.getCause() : ce);
            }
        }
        // We won the race — run the loader and publish the outcome.
        try {
            T val = doLoad(cacheName, effKey, fullKey, loader, ttlSec, cacheEmpty);
            mine.complete(val);
            return val;
        } catch (Throwable t) {
            loaderErrors.incrementAndGet();
            mine.completeExceptionally(t);
            throw propagate(t);
        } finally {
            inFlightLoads.remove(flightKey, mine);
        }
    }

    private <T> T doLoad(String cacheName, String effKey, String fullKey,
                         Supplier<T> loader, long ttlSec, boolean cacheEmpty) {
        loaderInvocations.incrementAndGet();
        T val = loader.get();
        if (val != null) {
            putInternal(cacheName, effKey, fullKey, val, ttlSec);
        } else if (cacheEmpty) {
            CacheManager cm = cmProvider.getIfAvailable();
            RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
            if (redis != null) {
                try {
                    redis.opsForValue().set(fullKey, EMPTY_SENTINEL, jitteredTtl(30), TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("L2 empty-cache fail: {}", e.getMessage());
                }
            }
            if (cm != null) {
                Cache c = cm.getCache(cacheName);
                if (c != null) c.put(effKey, EMPTY_SENTINEL);
            }
        }
        return val;
    }

    // ------------------------------------------------------------------
    // put / evict / evictAll
    // ------------------------------------------------------------------

    public void put(String cacheName, String key, Object value, long ttlSec) {
        putInternal(cacheName, key, cacheName + ":" + key, value, ttlSec);
    }

    /** Tenant-scoped variant of {@link #put(String, String, Object, long)}. */
    public void put(String cacheName, String tenantId, String key, Object value, long ttlSec) {
        String effKey = tenantKey(tenantId, key);
        putInternal(cacheName, effKey, cacheName + ":" + effKey, value, ttlSec);
    }

    private void putInternal(String cacheName, String effKey, String fullKey, Object value, long ttlSec) {
        RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                redis.opsForValue().set(fullKey, value, jitteredTtl(ttlSec), TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("L2 put fail: {}", e.getMessage());
            }
        }
        CacheManager cm = cmProvider.getIfAvailable();
        if (cm != null) {
            Cache c = cm.getCache(cacheName);
            if (c != null) c.put(effKey, value);
        }
    }

    /**
     * Applies the random TTL jitter factor (default 0.8~1.2) to a base TTL —
     * anti-avalanche so same-TTL keys do not all expire at once.
     */
    long jitteredTtl(long ttlSec) {
        double factor = 1.0;
        try {
            factor = ttlJitterRatio.getAsDouble();
        } catch (Exception ignored) {
            // a broken jitter source must never break caching
        }
        if (!Double.isFinite(factor) || factor <= 0) {
            factor = 1.0;
        }
        return Math.max(1L, Math.round(ttlSec * factor));
    }

    public void evict(String cacheName, String key) {
        evictInternal(cacheName, key, cacheName + ":" + key);
    }

    /** Tenant-scoped variant of {@link #evict(String, String)}. */
    public void evict(String cacheName, String tenantId, String key) {
        String effKey = tenantKey(tenantId, key);
        evictInternal(cacheName, effKey, cacheName + ":" + effKey);
    }

    private void evictInternal(String cacheName, String effKey, String fullKey) {
        // P1-8 (audit D-048): removed manual delayed-double-delete (virtual-thread
        // sleep + re-delete). That hand-rolled pattern violated the iron rule
        // "禁止手写已存在中间件内置能力". Cross-node L1 invalidation is now handled
        // by the Redis Pub/Sub CacheEvictionListener (P1-5), and L2 is deleted
        // synchronously below. The broadcast then notifies other nodes to clear
        // their L1, which closes the stale-read window the double-delete patched.
        CacheManager cm = cmProvider.getIfAvailable();
        if (cm != null) {
            Cache c = cm.getCache(cacheName);
            if (c != null) c.evict(effKey);
        }
        RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                redis.delete(fullKey);
            } catch (Exception e) {
                log.warn("L2 delete fail: {}", e.getMessage());
            }
        }
        CacheBroadcast b = broadcastProvider.getIfAvailable();
        if (b != null) b.publishEvict(cacheName, effKey);
    }

    /**
     * D-216: evict EVERY key under {@code cacheName} (L1 clear + L2 prefix
     * delete + cluster broadcast with the reserved "*" key). Needed for
     * pagination-style caches whose keys are unbounded (p1:10, p2:10, ...).
     *
     * <p>P0 (audit: KEYS 阻塞): the L2 prefix delete now uses a SCAN cursor
     * (batched, 500 per delete round-trip) instead of the O(N)-blocking
     * {@code KEYS pattern} command — Redis remains responsive on large
     * keyspaces.
     */
    public void evictAll(String cacheName) {
        CacheManager cm = cmProvider.getIfAvailable();
        if (cm != null) {
            Cache c = cm.getCache(cacheName);
            if (c != null) c.clear();
        }
        RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                    .match(cacheName + ":*")
                    .count(EVICTALL_SCAN_BATCH)
                    .build())) {
                List<String> batch = new ArrayList<>(EVICTALL_SCAN_BATCH);
                while (cursor.hasNext()) {
                    batch.add(cursor.next());
                    if (batch.size() >= EVICTALL_SCAN_BATCH) {
                        redis.delete(batch);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    redis.delete(batch);
                }
            } catch (Exception e) {
                log.warn("L2 evictAll fail for {}: {}", cacheName, e.getMessage());
            }
        }
        CacheBroadcast b = broadcastProvider.getIfAvailable();
        if (b != null) b.publishEvictAll(cacheName);
    }

    /** SCAN batch size — matches the commonly recommended ~500 keys per call. */
    static final int EVICTALL_SCAN_BATCH = 500;

    // ------------------------------------------------------------------
    // stats (audit: 命中率不可观测)
    // ------------------------------------------------------------------

    /**
     * Immutable snapshot of the hit/miss counters since service start.
     * Exported to Micrometer as {@code ep_cache_l1_hit_total},
     * {@code ep_cache_l1_miss_total}, {@code ep_cache_l2_hit_total},
     * {@code ep_cache_l2_miss_total} when Micrometer is on the classpath
     * (see {@link CacheConfig}); also logged periodically by
     * {@link CacheStatsLogger}.
     */
    /**
     * v7 (D-233): exposes the hotspot detector for Micrometer gauge binding
     * and diagnostics endpoints.
     */
    public HotspotDetector getHotspotDetector() {
        return hotspotDetector;
    }

    public CacheStats getStats() {
        return new CacheStats(
                l1Hits.get(), l1Misses.get(),
                l2Hits.get(), l2Misses.get(),
                loaderInvocations.get(), loaderErrors.get());
    }

    /** Counters snapshot. */
    public record CacheStats(
            long l1Hits,
            long l1Misses,
            long l2Hits,
            long l2Misses,
            long loaderInvocations,
            long loaderErrors) {

        /** L1 hit rate in [0.0, 1.0]; NaN-free (returns 0 when no lookups). */
        public double l1HitRate() {
            long total = l1Hits + l1Misses;
            return total == 0 ? 0.0 : (double) l1Hits / total;
        }

        /** L2 hit rate in [0.0, 1.0]; NaN-free (returns 0 when no lookups). */
        public double l2HitRate() {
            long total = l2Hits + l2Misses;
            return total == 0 ? 0.0 : (double) l2Hits / total;
        }

        /**
         * v7 (D-233): composite hit rate across BOTH tiers — every request
         * that did not fall through to the loader. SLO target ≥ 0.99
         * (industry practice for a two-tier cache fronting a database);
         * exported as {@code ep_cache_composite_hit_rate}.
         */
        public double compositeHitRate() {
            long total = l1Hits + l1Misses;
            if (total == 0) {
                return 0.0;
            }
            long servedFromCache = l1Hits + l2Hits;
            return (double) servedFromCache / total;
        }

        @Override
        public String toString() {
            return "CacheStats{l1Hit=" + l1Hits + ", l1Miss=" + l1Misses
                    + ", l1HitRate=" + String.format("%.1f%%", l1HitRate() * 100)
                    + ", l2Hit=" + l2Hits + ", l2Miss=" + l2Misses
                    + ", l2HitRate=" + String.format("%.1f%%", l2HitRate() * 100)
                    + ", loads=" + loaderInvocations + ", loadErrors=" + loaderErrors + "}";
        }
    }

    private static RuntimeException propagate(Throwable t) {
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error e) throw e;
        throw new IllegalStateException("cache load failed: " + t, t);
    }

    @SuppressWarnings("unchecked")
    private <T> T unwrap(T v) {
        return EMPTY_SENTINEL.equals(v) ? null : v;
    }
}
