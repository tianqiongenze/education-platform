package com.ep.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * v7 (D-233): hotspot key detector for the multi-tier cache.
 *
 * <p><b>Motivation (命中率提升空间闭环)</b>: cache hit-rate improvements are a
 * detect → observe → tune loop. The detector closes the <i>detect</i> leg:
 * every L2 miss that had to fall through to the loader is recorded per
 * {@code (cacheName, key)} in a sliding 60s window. A key that keeps missing
 * (≥ {@link #DEFAULT_THRESHOLD} fall-throughs within the window) is flagged
 * as a hotspot — the direct signal that either the L1 is too small (the
 * entry got LRU-evicted and immediately reloaded) or the TTL is too short
 * (the entry expired while still hot).</p>
 *
 * <p><b>Observe</b>: promotion counts are exported to Micrometer as
 * {@code ep_cache_hotspot_promoted_total} (per-cacheName tag via
 * {@link CacheMetrics} consumers) and periodic warnings are logged so
 * operators can size {@code ep.cache.l1.*} / TTLs from real evidence.</p>
 *
 * <p><b>Tune</b>: the detector itself never mutates cached values — it only
 * reports. Automatic pinning was deliberately rejected (v7 review): pinning
 * hot entries into a bounded L1 starves cold-but-soon-hot keys and re-creates
 * the eviction storm it tries to fix. The evidence-driven sizing loop is the
 * 2025 industry practice (hotspot detection feeds capacity planning, not
 * runtime overrides).</p>
 *
 * <p>Memory is bounded: the tracker holds at most {@link #MAX_TRACKED_KEYS}
 * entries; overflow sweeps idle windows opportunistically.</p>
 */
public class HotspotDetector {

    private static final Logger log = LoggerFactory.getLogger(HotspotDetector.class);

    /** Default fall-through count within the window that marks a hotspot. */
    public static final int DEFAULT_THRESHOLD = 5;

    /** Default sliding-window length in milliseconds. */
    public static final long DEFAULT_WINDOW_MILLIS = 60_000L;

    /** Upper bound on tracked keys to bound memory. */
    static final int MAX_TRACKED_KEYS = 50_000;

    private final int threshold;
    private final long windowMillis;
    private final ConcurrentHashMap<String, FallThroughLog> trackers = new ConcurrentHashMap<>();
    private final LongAdder promotedTotal = new LongAdder();

    /** Injectable clock for tests. */
    interface ClockMillis {
        long now();
    }

    private final ClockMillis clock;

    public HotspotDetector() {
        this(DEFAULT_THRESHOLD, DEFAULT_WINDOW_MILLIS, System::currentTimeMillis);
    }

    HotspotDetector(int threshold, long windowMillis, ClockMillis clock) {
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        if (windowMillis < 1) {
            throw new IllegalArgumentException("windowMillis must be positive");
        }
        this.threshold = threshold;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /**
     * Records a loader fall-through (L2 miss → DB load) for the key. When the
     * key accumulates ≥ {@code threshold} fall-throughs inside the sliding
     * window it is promoted to hotspot status exactly once per window.
     *
     * @return {@code true} if this call promoted the key to hotspot status
     */
    public boolean recordFallThrough(String cacheName, String effKey) {
        if (cacheName == null || effKey == null) {
            return false;
        }
        String composite = cacheName + ":" + effKey;
        FallThroughLog hitLog = trackers.compute(composite, (k, old) ->
                old == null ? new FallThroughLog(windowMillis, clock.now()) : old);
        boolean promoted = hitLog.record(clock.now(), threshold);
        if (promoted) {
            promotedTotal.increment();
            log.warn("[HotspotDetector] hotspot key promoted: cache={} key={} ({} loader fall-throughs in {}ms) "
                            + "-> consider raising ep.cache L1 capacity or TTL for this cache",
                    cacheName, effKey, threshold, windowMillis);
            if (trackers.size() > MAX_TRACKED_KEYS) {
                evictIdle();
            }
        }
        return promoted;
    }

    /** Total hotspot promotions since service start (Micrometer-exportable). */
    public long promotedTotal() {
        return promotedTotal.sum();
    }

    /** Snapshot of currently-hot keys (for diagnostics endpoints). */
    public List<Map.Entry<String, Long>> hotKeys() {
        long now = clock.now();
        return trackers.entrySet().stream()
                .filter(e -> e.getValue().countWithinWindow(now) >= threshold)
                .map(e -> Map.entry(e.getKey(), e.getValue().countWithinWindow(now)))
                .toList();
    }

    private void evictIdle() {
        long now = clock.now();
        trackers.entrySet().removeIf(e -> e.getValue().isIdle(now, windowMillis));
    }

    /** Two-bucket sliding window per key — O(1) memory per tracked key. */
    private static final class FallThroughLog {
        private final long windowMillis;
        private final AtomicLong currentBucketStart;
        private final AtomicLong currentCount = new AtomicLong();
        private final AtomicLong previousCount = new AtomicLong();
        private final AtomicBoolean promotedInWindow = new AtomicBoolean(false);

        FallThroughLog(long windowMillis, long now) {
            this.windowMillis = windowMillis;
            this.currentBucketStart = new AtomicLong(bucketStart(now));
        }

        private long bucketStart(long now) {
            return now / (windowMillis / 2) * (windowMillis / 2);
        }

        synchronized boolean record(long now, int threshold) {
            long expected = currentBucketStart.get();
            long actualBucket = bucketStart(now);
            if (actualBucket > expected) {
                previousCount.set(currentCount.get());
                currentCount.set(0);
                currentBucketStart.set(actualBucket);
                if (actualBucket - expected >= windowMillis) {
                    // skipped at least a full window — promotion flag resets
                    promotedInWindow.set(false);
                }
            }
            long count = currentCount.incrementAndGet() + previousCount.get();
            if (count >= threshold && promotedInWindow.compareAndSet(false, true)) {
                return true;
            }
            if (count < threshold / 2) {
                // traffic dropped well below hot level — allow future promotion
                promotedInWindow.set(false);
            }
            return false;
        }

        synchronized long countWithinWindow(long now) {
            long expected = currentBucketStart.get();
            long actualBucket = bucketStart(now);
            if (actualBucket > expected) {
                previousCount.set(currentCount.get());
                currentCount.set(0);
                currentBucketStart.set(actualBucket);
            }
            return currentCount.get() + previousCount.get();
        }

        synchronized boolean isIdle(long now, long windowMillis) {
            return bucketStart(now) - currentBucketStart.get() >= 2 * windowMillis;
        }
    }
}
