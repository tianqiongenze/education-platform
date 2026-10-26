package com.ep.web.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Risk-control P0-3 (v7, D-233): registers the {@link RiskControlFilter} for
 * every web service that carries ep-web-common. Enabled by default
 * ({@code ep.risk.enabled=true} unless overridden) — rate limiting that ships
 * disabled is rate limiting that never ships.
 *
 * <p>Limits (requests per {@code ep.risk.window-seconds} sliding window,
 * default 60s): {@code ep.risk.limits.auth-per-minute} (default 5 for
 * {@code /api/auth/**}) and {@code ep.risk.limits.default-per-minute}
 * (default 60 elsewhere). {@code ep.risk.trusted-proxies} (default 0)
 * controls how many reverse proxies in front of this service are trusted
 * when resolving the client IP from {@code X-Forwarded-For}.</p>
 *
 * <p>Counting prefers Redis ({@link RedisSlidingWindowCounter}, cluster-wide
 * sliding window with half-open self-healing) when both spring-data-redis and
 * a {@link StringRedisTemplate} bean are present, and degrades to the
 * per-instance {@link RiskControlFilter.MemorySlidingWindowCounter} otherwise.
 * The Redis branch is isolated in a nested configuration class so consumers
 * without spring-data-redis on the classpath can still load this
 * auto-configuration.</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(OncePerRequestFilter.class)
@ConditionalOnProperty(name = "ep.risk.enabled", havingValue = "true", matchIfMissing = true)
public class RiskControlAutoConfiguration {

    /**
     * The filter bean. The {@link RiskControlFilter.WindowCounter} is injected
     * as an optional dependency: the Redis branch below contributes a
     * cluster-wide sliding-window counter when possible, otherwise the
     * in-memory fallback is created here.
     */
    @Bean
    @ConditionalOnMissingBean
    public RiskControlFilter riskControlFilter(
            ObjectProvider<RiskControlFilter.WindowCounter> counterProvider,
            @Value("${ep.risk.limits.auth-per-minute:5}") int authLimitPerMinute,
            @Value("${ep.risk.limits.default-per-minute:60}") int defaultLimitPerMinute,
            @Value("${ep.risk.window-seconds:60}") int windowSeconds,
            @Value("${ep.risk.trusted-proxies:0}") int trustedProxies,
            ObjectProvider<com.ep.web.common.risk.IpBanService> ipBanServiceProvider) {
        RiskControlFilter.WindowCounter counter =
                counterProvider.getIfAvailable(RiskControlFilter::memoryCounter);
        return new RiskControlFilter(counter, authLimitPerMinute, defaultLimitPerMinute,
                windowSeconds, trustedProxies, java.time.Clock.systemUTC(),
                ipBanServiceProvider.getIfAvailable());
    }

    /**
     * D-236: automatic IP ban above the rate limiter (3 strikes → 30 min ban),
     * enabled by default and deactivatable via {@code ep.risk.ban.enabled=false}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "ep.risk.ban.enabled", havingValue = "true", matchIfMissing = true)
    public com.ep.web.common.risk.IpBanService ipBanService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${ep.risk.ban.violations-to-ban:3}") int violationsToBan,
            @Value("${ep.risk.ban.ttl-minutes:30}") int banTtlMinutes) {
        return new com.ep.web.common.risk.IpBanService(redisProvider.getIfAvailable(),
                violationsToBan, java.time.Duration.ofMinutes(banTtlMinutes));
    }

    /** Redis-backed sliding-window counter, only when spring-data-redis AND a template exist. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    static class RedisCounterConfiguration {

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean(RiskControlFilter.WindowCounter.class)
        public RiskControlFilter.WindowCounter riskControlWindowCounter(StringRedisTemplate redisTemplate) {
            return new RedisSlidingWindowCounter(redisTemplate);
        }
    }
}
