package com.ep.web.common.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
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
 * D-227: registers the {@link JwtRevocationFilter} (logout revocation blacklist
 * check) for every servlet web service that carries ep-web-common. Enabled by
 * default ({@code ep.jwt-revocation.enabled=true} unless overridden).
 *
 * <p>The blacklist prefers Redis ({@link JwtBlacklistService} backed by
 * {@link StringRedisTemplate}); when spring-data-redis or the template bean is
 * absent the filter is still registered but backed by a no-op service that
 * never rejects — the revocation feature simply stays dormant instead of
 * breaking consumers without Redis.</p>
 */
@AutoConfiguration
// Referenced by name (not class) on purpose: ep-web-common must not need a
// compile-time dependency on the Redis auto-configuration, whose package moved
// between Boot 3 (org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration)
// and Boot 4 (org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration).
// The ordering only matters so @ConditionalOnBean(StringRedisTemplate) below is
// evaluated after Redis has contributed its template beans.
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(OncePerRequestFilter.class)
@ConditionalOnProperty(name = "ep.jwt-revocation.enabled", havingValue = "true", matchIfMissing = true)
public class JwtRevocationAutoConfiguration {

    /**
     * The filter bean. The {@link JwtBlacklistService} is injected as an
     * optional dependency so consumers without Redis still boot (the filter
     * then degrades to a pass-through).
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtRevocationFilter jwtRevocationFilter(ObjectProvider<JwtBlacklistService> blacklistProvider) {
        return new JwtRevocationFilter(
                blacklistProvider.getIfAvailable(() -> new JwtBlacklistService(null)));
    }

    /** Redis-backed blacklist, only when spring-data-redis AND a template exist. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    static class BlacklistConfiguration {

        @Bean
        @ConditionalOnBean(StringRedisTemplate.class)
        @ConditionalOnMissingBean
        public JwtBlacklistService jwtBlacklistService(StringRedisTemplate redisTemplate) {
            return new JwtBlacklistService(redisTemplate);
        }
    }
}
