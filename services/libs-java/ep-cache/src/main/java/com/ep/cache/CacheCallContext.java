package com.ep.cache;

import java.util.Optional;

/**
 * Per-thread, per-call cache-hit metadata (OSW observability iron rule:
 * the cache-hit info computed in the cache layer must be able to reach the
 * HTTP response, not stay in aggregate {@code ep_cache_*_total} counters
 * alone).
 *
 * <p>{@link CacheService#getOrLoad} records, for every call on the calling
 * thread, the outcome of that single call: the hit level ({@link Level#L1}
 * local Caffeine, {@link Level#L2} Redis, or {@link Level#NONE} — fell
 * through to the loader), the effective cache key (tenant prefix included
 * for the tenant-scoped overloads) and the call latency in microseconds.
 *
 * <p><b>Lifecycle contract (leak prevention).</b> The context is purely
 * per-thread and persists across calls until {@link #clear()} — there is no
 * implicit clearing, so a context value is always exactly the last cache op
 * on this thread. The HTTP/web layer that fronts the cache owns the
 * clearing: the reference consumer, portal-bff's {@code ProxyController},
 * reads {@link #snapshot()} and clears the context in a {@code finally}
 * block at the end of the cache-backed request branch. Requests that bypass
 * the cache never record anything, so nothing leaks on those paths.
 *
 * <p>Servers that reuse worker threads (Tomcat/virtual threads) MUST follow
 * this contract or stale values would leak across requests.
 */
public final class CacheCallContext {

    /** Which tier served the call. */
    public enum Level { NONE, L1, L2, L3 }

    /** Immutable snapshot of one cache operation. */
    public record Hit(Level level, String key, long latencyMicros) {
        /** True when the value was served from a cache tier (not the loader). */
        public boolean hit() {
            return level != Level.NONE;
        }
    }

    private static final ThreadLocal<Hit> CURRENT = new ThreadLocal<>();

    private CacheCallContext() {
    }

    /**
     * Records the outcome of one cache operation on the current thread.
     * Called by {@link CacheService} at the end of each {@code getOrLoad};
     * also visible to tests in the same package for direct injection.
     */
    static void record(Level level, String key, long latencyMicros) {
        CURRENT.set(new Hit(level, key, Math.max(0L, latencyMicros)));
    }

    /**
     * Snapshot of the last cache operation on this thread; empty when no
     * operation was recorded since the last {@link #clear()}.
     */
    public static Optional<Hit> snapshot() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Clears this thread's context. Must be invoked once per request by the
     * web layer (finally block or filter) to prevent cross-request leaks.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
