package com.ep.bff.config;

import com.ep.bff.edge.GrayInterceptor;
import com.ep.bff.edge.IdempotentInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * WebMVC interceptor registration (SDD §5.1 GatewayConfig).
 *
 * <p>Registers the SDD-designed interceptors into the WebMVC interceptor chain:
 * <ul>
 *   <li>{@link IdempotentInterceptor} — idempotency key dedup (when enabled)</li>
 *   <li>{@link GrayInterceptor} — gray/canary traffic split (when enabled)</li>
 * </ul>
 *
 * <p>The existing filters ({@code AuthFilter}, {@code TraceIdFilter},
 * {@code CellRouterFilter}) remain as Servlet Filters — they run before
 * the interceptor chain. Interceptors operate at the WebMVC layer, after
 * filters have processed the request.
 *
 * <p>Interceptor order (after filters):
 * <ol>
 *   <li>IdempotentInterceptor — check duplicate before processing</li>
 *   <li>GrayInterceptor — tag traffic for gray release</li>
 * </ol>
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

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (idempotentEnabled) {
            registry.addInterceptor(new IdempotentInterceptor(redisTemplate))
                    .addPathPatterns("/api/**")
                    .order(0);
            log.info("[GatewayInterceptorConfig] IdempotentInterceptor registered (paths=/api/**)");
        }

        if (grayEnabled) {
            registry.addInterceptor(new GrayInterceptor(grayRatio))
                    .addPathPatterns("/api/**")
                    .order(1);
            log.info("[GatewayInterceptorConfig] GrayInterceptor registered (ratio={})", grayRatio);
        }
    }
}
