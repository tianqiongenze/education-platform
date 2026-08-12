package com.ep.cache;

/**
 * SPI for cache warm-up. Implementations register named warmers that are
 * invoked on application start and (optionally) on a schedule.
 *
 * <p>D-073: Replaces the J-KB-04 XXL-Job design with a lightweight Spring
 * {@code @Scheduled} approach — no extra scheduling center needed.
 */
public interface CacheWarmer {

    /** Logical cache name this warmer targets (e.g. "qa"). */
    String cacheName();

    /**
     * Pre-populate the cache. Called once on startup and periodically
     * by scheduled warmers. Implementations should be idempotent and
     * best-effort — exceptions are caught by the runner.
     */
    void warm(CacheService cacheService);
}
