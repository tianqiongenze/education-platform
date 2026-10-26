package com.ep.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically logs the {@link CacheService#getStats()} snapshot — the
 * zero-dependency observability fallback for hosts without Micrometer
 * (audit fix: 命中率不可观测).
 *
 * <p>Configurable via {@code ep.cache.stats-log-interval-sec} (default 60);
 * a value {@code <= 0} disables the logger entirely (no thread is started).
 * The worker is a daemon thread, so it never delays JVM shutdown.
 */
public class CacheStatsLogger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CacheStatsLogger.class);

    private final ScheduledExecutorService scheduler;

    public CacheStatsLogger(CacheService cacheService, long intervalSec) {
        if (cacheService == null || intervalSec <= 0) {
            this.scheduler = null;
            return;
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ep-cache-stats-logger");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(() -> {
            try {
                CacheService.CacheStats stats = cacheService.getStats();
                log.info("ep-cache stats: {}", stats);
                // v7 (D-233): composite hit-rate SLO check — industry target for
                // a two-tier cache is >= 99%. A sustained gap is the actionable
                // signal to raise L1 capacity, lengthen TTLs, or investigate
                // hotspots reported by HotspotDetector.
                if (stats.l1Hits() + stats.l1Misses() >= 1000) {
                    double composite = stats.compositeHitRate();
                    if (composite < 0.99) {
                        log.warn("ep-cache SLO: composite hit rate {} below 99% target "
                                + "(L1={}/{}, L2={}/{}, loads={}) — tune ep.cache L1 capacity/TTL "
                                + "and review HotspotDetector output",
                                String.format("%.2f%%", composite * 100),
                                stats.l1Hits(), stats.l1Hits() + stats.l1Misses(),
                                stats.l2Hits(), stats.l2Hits() + stats.l2Misses(),
                                stats.loaderInvocations());
                    }
                }
            } catch (Exception e) {
                log.debug("ep-cache stats log failed: {}", e.toString());
            }
        }, intervalSec, intervalSec, TimeUnit.SECONDS);
    }

    public boolean isEnabled() {
        return scheduler != null;
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
