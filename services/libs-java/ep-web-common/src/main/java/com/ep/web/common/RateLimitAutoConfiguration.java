package com.ep.web.common;

import io.github.resilience4j.common.ratelimiter.configuration.RateLimiterConfigCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.time.Duration;

/**
 * D-154 P2-10: Enables Resilience4j RateLimiter AOP for all services that
 * have ep-web-common on the classpath. Individual controllers/methods can
 * opt in with @RateLimiter("default"). The rate-limit parameters are
 * configured in each service's application.yml under
 * resilience4j.ratelimiter.configs.default.
 *
 * <h3>Risk-control P0-3: rate limiting is now ON by default</h3>
 * {@code ep.ratelimit.enabled} was previously {@code matchIfMissing=false},
 * so unless a service opted in via YAML the @RateLimiter aspect was never
 * registered — the annotation silently did nothing. The flag now defaults to
 * <b>true</b>; services that need to opt out set {@code ep.ratelimit.enabled=false}.
 *
 * <p>Conservative code-side default: when the application has NOT configured
 * {@code resilience4j.ratelimiter.configs.default.limit-for-period} explicitly,
 * a {@link RateLimiterConfigCustomizer} pins the shared {@code default}
 * configuration to <b>10 requests/second per service instance</b> (1s refresh,
 * 1s wait timeout). Explicit YAML always wins — the customizer is skipped when
 * the property is present, so ops-configured limits (e.g. portal-bff's 100/s)
 * are never overridden by code.</p>
 *
 * <p>Annotation-level rate limiting protects individual annotated endpoints;
 * the cross-cutting IP/user request-rate floor for every other path is enforced
 * by {@link RiskControlAutoConfiguration}/{@link RiskControlFilter}.</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "io.github.resilience4j.ratelimiter.RateLimiter")
@ConditionalOnProperty(name = "ep.ratelimit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitAutoConfiguration {

    /** Conservative default: 10 permits per 1-second refresh window, 1s wait. */
    static final int DEFAULT_LIMIT_FOR_PERIOD = 10;

    /**
     * Conservative fallback parameters for the shared {@code default} config,
     * applied only when the application did not pin
     * {@code resilience4j.ratelimiter.configs.default.limit-for-period} in YAML.
     * Resilience4j customizers only act on configurations that exist in the
     * YAML {@code configs} block, so this is a no-op for services that define
     * no {@code default} config at all (they keep Resilience4j's registry
     * defaults) and never overrides an explicit value.
     */
    @Bean
    @ConditionalOnProperty(name = "ep.ratelimit.conservative-default", havingValue = "true",
            matchIfMissing = true)
    public RateLimiterConfigCustomizer epConservativeDefaultRateLimiter(Environment environment) {
        boolean explicitlyConfigured = environment.containsProperty(
                "resilience4j.ratelimiter.configs.default.limit-for-period");
        if (explicitlyConfigured) {
            // Application pinned its own limit — do not tighten it from code.
            return RateLimiterConfigCustomizer.of("ep-never-matches-noop", builder -> { });
        }
        return RateLimiterConfigCustomizer.of("default", builder -> builder
                .limitForPeriod(DEFAULT_LIMIT_FOR_PERIOD)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(1)));
    }
}
