package com.ep.web.common;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * D-154 P2-10: Enables Resilience4j RateLimiter AOP for all services that
 * have ep-web-common on the classpath. Individual controllers/methods can
 * opt in with @RateLimiter("default"). The rate-limit parameters are
 * configured in each service's application.yml under
 * resilience4j.ratelimiter.configs.default.
 */
@AutoConfiguration
@ConditionalOnClass(name = "io.github.resilience4j.ratelimiter.RateLimiter")
@ConditionalOnProperty(name = "ep.ratelimit.enabled", havingValue = "true", matchIfMissing = false)
public class RateLimitAutoConfiguration {
    // Auto-config: Resilience4j Spring Boot starter auto-registers the
    // @RateLimiter AOP aspect when resilience4j-spring-boot3 is on the
    // classpath and ep.ratelimit.enabled=true. No additional beans needed.
}
