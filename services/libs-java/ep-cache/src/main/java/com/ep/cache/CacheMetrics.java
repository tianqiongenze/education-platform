package com.ep.cache;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Exports the {@link CacheService} hit/miss counters to Micrometer as
 * monotonic counters (audit fix: 命中率不可观测):
 * <ul>
 *   <li>{@code ep_cache_l1_hit_total} / {@code ep_cache_l1_miss_total}</li>
 *   <li>{@code ep_cache_l2_hit_total} / {@code ep_cache_l2_miss_total}</li>
 * </ul>
 *
 * <p>Registered only when Micrometer is on the classpath — see
 * {@link CacheMetricsConfig} ({@code @ConditionalOnClass(MeterRegistry)}).
 * ep-cache declares micrometer-core as {@code provided}, so hosts without
 * Micrometer carry no new dependency.
 */
public class CacheMetrics implements MeterBinder {

    public static final String L1_HIT = "ep_cache_l1_hit_total";
    public static final String L1_MISS = "ep_cache_l1_miss_total";
    public static final String L2_HIT = "ep_cache_l2_hit_total";
    public static final String L2_MISS = "ep_cache_l2_miss_total";
    /** v7 (D-233): hotspot keys promoted by the fall-through detector. */
    public static final String HOTSPOT_PROMOTED = "ep_cache_hotspot_promoted_total";
    /** v7 (D-233): composite (L1+L2) hit rate gauge — SLO target ≥ 0.99. */
    public static final String COMPOSITE_HIT_RATE = "ep_cache_composite_hit_rate";

    private final CacheService cacheService;

    public CacheMetrics(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        FunctionCounter.builder(L1_HIT, cacheService, s -> s.getStats().l1Hits())
                .description("L1 (Caffeine) cache hits")
                .register(registry);
        FunctionCounter.builder(L1_MISS, cacheService, s -> s.getStats().l1Misses())
                .description("L1 (Caffeine) cache misses")
                .register(registry);
        FunctionCounter.builder(L2_HIT, cacheService, s -> s.getStats().l2Hits())
                .description("L2 (Redis) cache hits")
                .register(registry);
        FunctionCounter.builder(L2_MISS, cacheService, s -> s.getStats().l2Misses())
                .description("L2 (Redis) cache misses")
                .register(registry);
        FunctionCounter.builder(HOTSPOT_PROMOTED, cacheService.getHotspotDetector(),
                        HotspotDetector::promotedTotal)
                .description("Hotspot keys promoted by the loader fall-through detector (v7)")
                .register(registry);
        io.micrometer.core.instrument.Gauge.builder(COMPOSITE_HIT_RATE, cacheService,
                        s -> s.getStats().compositeHitRate())
                .description("Composite (L1+L2) hit rate; SLO target >= 0.99 (v7)")
                .register(registry);
    }
}
