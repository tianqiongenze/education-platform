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
                log.info("ep-cache stats: {}", cacheService.getStats());
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
