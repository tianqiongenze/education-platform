package com.ep.bff.config;

import com.ep.bff.edge.GrayInterceptor;
import com.ep.bff.edge.IdempotencyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * WebMVC interceptor &amp; servlet filter registration (SDD §5.1 GatewayConfig).
 *
 * <p>Registers the SDD-designed edge components:
 * <ul>
 *   <li>{@link IdempotencyFilter} — idempotency key dedup with Stripe-style
 *       response replay (v7, D-233). Registered as a servlet <b>filter</b>
 *       because response capture requires a
 *       {@code ContentCachingResponseWrapper}, which only a filter can
 *       install.</li>
 *   <li>{@link GrayInterceptor} — gray/canary traffic split (when enabled),
 *       still a WebMVC interceptor.</li>
 * </ul>
 *
 * <p>The existing filters ({@code AuthFilter}, {@code TraceIdFilter},
 * {@code CellRouterFilter}) run before the idempotency filter — the
 * idempotency layer sits closest to the handler so it observes the exact
 * request the business logic will process.
 *
 * <p>The whole configuration is gated on a {@link StringRedisTemplate} bean
 * being present (provided by {@code spring-boot-starter-data-redis}). This
 * keeps the BFF bootstrap-safe in environments where Redis is not yet
 * configured — e.g. during unit tests that don't load the full context.
 */
@Configuration
@ConditionalOnBean(StringRedisTemplate.class)
public class GatewayInterceptorConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(GatewayInterceptorConfig.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${ep.idempotent.enabled:true}")
    private boolean idempotentEnabled;

    @Value("${ep.gray.enabled:false}")
    private boolean grayEnabled;

    @Value("${ep.gray.ratio:0.0}")
    private double grayRatio;

    public GatewayInterceptorConfig(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Idempotency filter — registered at the highest filter precedence after auth/trace. */
    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration() {
        FilterRegistrationBean<IdempotencyFilter> registration =
                new FilterRegistrationBean<>(new IdempotencyFilter(redisTemplate));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setEnabled(idempotentEnabled);
        registration.setName("idempotencyFilter");
        log.info("[GatewayInterceptorConfig] IdempotencyFilter registered (paths=/api/*, enabled={})",
                idempotentEnabled);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (grayEnabled) {
            registry.addInterceptor(new GrayInterceptor(grayRatio))
                    .addPathPatterns("/api/**")
                    .order(1);
            log.info("[GatewayInterceptorConfig] GrayInterceptor registered (ratio={})", grayRatio);
        }
    }
}
