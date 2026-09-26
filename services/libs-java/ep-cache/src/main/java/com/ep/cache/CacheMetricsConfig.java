package com.ep.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Micrometer {@link CacheMetrics} binder when Micrometer is on
 * the host classpath. {@code @ConditionalOnClass} is evaluated from bytecode
 * metadata, so this configuration (and the micrometer-referencing types) is
 * never loaded on hosts without Micrometer — no hard dependency is added.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(MeterRegistry.class)
public class CacheMetricsConfig {

    @Bean
    @ConditionalOnMissingBean
    public CacheMetrics cacheMetrics(CacheService cacheService) {
        return new CacheMetrics(cacheService);
    }
}
