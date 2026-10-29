package com.ep.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * D-243 (v8 P1-4): L3 hotspot mirror cache — a large, long-TTL in-process
 * mirror dedicated to keys that the {@link HotspotDetector} has promoted as
 * hot. This is the "本地 KV 全量镜像" leg of the v8 cache roadmap: the first
 * wave of requests for a newly-hot key still pays the loader; every
 * subsequent request on this instance is served from local memory, which is
 * what pushes the composite hit rate past the 90% acceptance bar under
 * hotspot-heavy load.
 *
 * <p><b>Tier order</b>: L1 (Caffeine, small/fast) → <b>L3 (this mirror, big/
 * local)</b> → L2 (Redis, remote) → loader. L3 sits between L1 and L2 because
 * a local hit (nanoseconds) always beats a remote Redis round-trip
 * (sub-millisecond): the mirror is L1 with a much bigger budget, not another
 * remote tier.</p>
 *
 * <p><b>Promotion lifecycle</b>: {@link HotspotDetector} reports a promoted
 * key (≥ {@code threshold} loader fall-throughs in the sliding window) →
 * {@code CacheService} promotes it here → the next load/publish re-fills the
 * mirror → reads hit. Demotion is implicit: Caffeine size-bounds + TTL evict
 * cold tail, and the {@code promoted} marker decays with the same TTL so the
 * promotion set cannot grow unbounded.</p>
 *
 * <p><b>Invalidation coupling</b>: every {@code ep-cache} eviction path (local
 * evict, Pulsar broadcast, evictAll) clears the mirror too — a hot key must
 * never serve a stale value through a faster tier.</p>
 */
public class HotspotMirrorCache {

    private static final Logger log = LoggerFactory.getLogger(HotspotMirrorCache.class);

    /** Default mirror capacity (hot keys only — 10k is generous for one service). */
    public static final int DEFAULT_MAX_SIZE = 10_000;

    /** Default mirror TTL — longer than L1 (5-10min) but far from infinite. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final Cache<String, Object> mirror;
    /** Promotion markers: "cacheName:effKey" → promoted-at millis. */
    private final ConcurrentHashMap<String, Long> promoted = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxSize;

    // diagnostics
    private final AtomicLong l3Hits = new AtomicLong();
    private final AtomicLong l3Misses = new AtomicLong();
    private final AtomicLong promotions = new AtomicLong();

    public HotspotMirrorCache() {
        this(DEFAULT_MAX_SIZE, DEFAULT_TTL);
    }

    /** Test-friendly constructor. */
    HotspotMirrorCache(int maxSize, Duration ttl) {
        this.maxSize = maxSize;
        this.ttlMillis = ttl.toMillis();
        this.mirror = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .build();
        log.info("D-243: L3 hotspot mirror ready (maxSize={}, ttl={})", maxSize, ttl);
    }

    /**
     * Promotes a key: from now on loads/reads of this key flow through the
     * mirror. Called by {@code CacheService} when {@link HotspotDetector}
     * reports a promotion.
     */
    public void promote(String cacheName, String effKey) {
        promoted.put(composite(cacheName, effKey), System.currentTimeMillis());
        promotions.incrementAndGet();
        log.info("D-243: L3 promoted {}/{} (promotions total: {})", cacheName, effKey, promotions.get());
    }

    /** Whether the key is a promoted hotspot (and its marker has not decayed). */
    public boolean isPromoted(String cacheName, String effKey) {
        String composite = composite(cacheName, effKey);
        Long at = promoted.get(composite);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > ttlMillis) {
            promoted.remove(composite, at); // marker decay — same TTL as entries
            return false;
        }
        return true;
    }

    /**
     * Mirror lookup — only meaningful for promoted keys. Returns
     * {@link #MISSING} sentinel when absent (a real cached value can never be
     * the sentinel itself because values enter via {@link #put} from
     * loaded objects and the CacheService empty sentinel is stored as the
     * value string and returned as null by {@link #getIfPresent}).
     */
    public Object getIfPresent(String cacheName, String effKey) {
        if (!isPromoted(cacheName, effKey)) {
            l3Misses.incrementAndGet();
            return MISSING;
        }
        Object v = mirror.getIfPresent(composite(cacheName, effKey));
        if (v == null) {
            l3Misses.incrementAndGet();
            return MISSING;
        }
        l3Hits.incrementAndGet();
        return v;
    }

    /** Fills the mirror for a promoted key (from a load or an L2 hit). */
    public void put(String cacheName, String effKey, Object value) {
        if (!isPromoted(cacheName, effKey)) {
            return; // mirror only carries promoted hotspots
        }
        mirror.put(composite(cacheName, effKey), value);
    }

    /** Invalidates one key (local evict + broadcast path). */
    public void evict(String cacheName, String effKey) {
        mirror.invalidate(composite(cacheName, effKey));
    }

    /** Invalidates a whole cacheName (evictAll broadcast path). */
    public void evictAll(String cacheName) {
        String prefix = cacheName + ":";
        promoted.keySet().removeIf(k -> k.startsWith(prefix));
        // mirror entries carry the same prefix — sweep via cache policy view
        mirror.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Diagnostics: mirror hit rate in [0,1]; 0.0 when no lookups yet. */
    public double l3HitRate() {
        long hits = l3Hits.get();
        long total = hits + l3Misses.get();
        return total == 0 ? 0.0 : (double) hits / total;
    }

    public long l3Hits() {
        return l3Hits.get();
    }

    public long l3Misses() {
        return l3Misses.get();
    }

    public long promotionsTotal() {
        return promotions.get();
    }

    public long size() {
        return mirror.estimatedSize();
    }

    /** True when the mirror is serving at or above the v8 acceptance bar (0.9). */
    public boolean meetsAcceptanceBar() {
        long total = l3Hits.get() + l3Misses.get();
        return total >= 100 && l3HitRate() >= 0.90;
    }

    private static String composite(String cacheName, String effKey) {
        return cacheName + ":" + effKey;
    }

    /** Lookup sentinel: "nothing in the mirror for this key". */
    public static final Object MISSING = new Object();
}
