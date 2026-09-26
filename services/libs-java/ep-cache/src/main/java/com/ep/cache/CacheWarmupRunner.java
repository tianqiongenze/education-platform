package com.ep.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * On application start, invokes all registered {@link CacheWarmer} beans
 * concurrently using virtual threads (D-039). Each warmer is given a 30s
 * timeout; failures are logged at WARN and never block startup.
 *
 * <p>D-073: replaces the J-KB-04 XXL-Job warm-up design with an in-process
 * Spring-managed approach.
 */
@org.springframework.stereotype.Component
@ConditionalOnProperty(name = "ep.cache.warmup.enabled", matchIfMissing = true)
public class CacheWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmupRunner.class);
    private static final long WARMER_TIMEOUT_SEC = 30;

    private final ObjectProvider<CacheService> cacheServiceProvider;
    private final ObjectProvider<CacheWarmer> warmersProvider;

    public CacheWarmupRunner(ObjectProvider<CacheService> cacheServiceProvider,
                             ObjectProvider<CacheWarmer> warmersProvider) {
        this.cacheServiceProvider = cacheServiceProvider;
        this.warmersProvider = warmersProvider;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        CacheService cacheService = cacheServiceProvider.getIfAvailable();
        if (cacheService == null) {
            log.info("CacheService unavailable — skipping cache warm-up");
            return;
        }
        if (!cacheService.isEnabled()) {
            log.info("Cache disabled — skipping cache warm-up");
            return;
        }

        List<CacheWarmer> warmers = warmersProvider.orderedStream().toList();
        if (warmers.isEmpty()) {
            log.info("No CacheWarmer beans registered — nothing to warm");
            return;
        }

        log.info("Warming {} cache warmer(s) on virtual threads", warmers.size());
        // D-039: virtual threads for non-blocking async warm-up
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (CacheWarmer warmer : warmers) {
                submitWarmer(executor, warmer, cacheService);
            }
        }
        log.info("Cache warm-up complete");
    }

    private void submitWarmer(ExecutorService executor, CacheWarmer warmer, CacheService cacheService) {
        Future<?> future = executor.submit(() -> {
            try {
                warmer.warm(cacheService);
            } catch (Exception e) {
                log.warn("CacheWarmer '{}' threw during warm-up: {}", warmer.cacheName(), e.getMessage());
            }
        });
        try {
            future.get(WARMER_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("CacheWarmer '{}' did not complete within {}s: {}",
                    warmer.cacheName(), WARMER_TIMEOUT_SEC, e.getMessage());
            future.cancel(true);
        }
    }
}
