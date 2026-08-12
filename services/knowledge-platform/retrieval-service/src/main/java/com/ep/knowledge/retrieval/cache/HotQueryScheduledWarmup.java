package com.ep.knowledge.retrieval.cache;

import com.ep.cache.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic hot-query cache warm-up — J-KB-04 design, every 2h.
 *
 * <p>D-073: replaces the original XXL-Job scheduling center with Spring
 * {@code @Scheduled}; D-041 multi-active compatible (each node warms
 * independently; idempotent writes to the shared L2 Redis).
 */
@Component
@ConditionalOnProperty(name = "ep.cache.warmup.hot-query", matchIfMissing = true)
public class HotQueryScheduledWarmup {

    private static final Logger log = LoggerFactory.getLogger(HotQueryScheduledWarmup.class);

    private final HotQueryCacheWarmer hotQueryCacheWarmer;
    private final ObjectProvider<CacheService> cacheServiceProvider;

    public HotQueryScheduledWarmup(HotQueryCacheWarmer hotQueryCacheWarmer,
                                   ObjectProvider<CacheService> cacheServiceProvider) {
        this.hotQueryCacheWarmer = hotQueryCacheWarmer;
        this.cacheServiceProvider = cacheServiceProvider;
    }

    /** J-KB-04: every 2 hours. */
    @Scheduled(fixedDelay = 2 * 60 * 60 * 1000)
    public void warmPeriodically() {
        CacheService cacheService = cacheServiceProvider.getIfAvailable();
        if (cacheService == null || !cacheService.isEnabled()) {
            log.debug("Cache unavailable — skipping scheduled hot-query warm-up");
            return;
        }
        hotQueryCacheWarmer.warm(cacheService);
    }
}
