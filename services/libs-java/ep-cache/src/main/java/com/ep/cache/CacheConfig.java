package com.ep.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
@ConditionalOnProperty(name = "ep.cache.enabled", matchIfMissing = true)
public class CacheConfig {

    /**
     * L1 Caffeine cache manager.
     *
     * <p>Audit fixes:
     * <ul>
     *   <li>{@code recordStats()} enabled on every cache (default + custom) so
     *       hit rates are observable via {@link CacheService#getStats()} /
     *       {@code CacheMetrics}.</li>
     *   <li>Per-cache-name {@code maximumSize}/{@code expireAfterWrite} via
     *       {@code ep.cache.specs.<name>.*} (defaults stay 10000/60s) — see
     *       {@link CacheProperties}.</li>
     * </ul>
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(org.springframework.cache.CacheManager.class)
    public CaffeineCacheManager cacheManager(CacheProperties props) {
        CaffeineCacheManager m = new CaffeineCacheManager();
        // brief 修正：caffeine.caffeine() 不存在 -> 用 Caffeine.newBuilder()
        // Default spec for dynamically created caches (10000/60s preserved).
        m.setCaffeine(Caffeine.newBuilder()
                .maximumSize(props.getDefaultMaximumSize())
                .expireAfterWrite(Duration.ofSeconds(props.getDefaultExpireAfterWriteSec()))
                .recordStats());
        // Pre-register caches with an explicit per-name spec override.
        props.getSpecs().forEach((name, spec) -> {
            Cache<Object, Object> custom = Caffeine.newBuilder()
                    .maximumSize(props.resolveMaximumSize(name))
                    .expireAfterWrite(Duration.ofSeconds(props.resolveExpireAfterWriteSec(name)))
                    .recordStats()
                    .build();
            m.registerCustomCache(name, custom);
        });
        return m;
    }

    /**
     * Registers {@link CacheService} as a bean. It used to rely solely on
     * {@code @Component}, but no host app component-scans {@code com.ep.cache},
     * so the auto-configuration must provide it for injection sites such as
     * CourseServiceImpl / ProxyController.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public CacheService cacheService(ObjectProvider<org.springframework.cache.CacheManager> cm,
                                     ObjectProvider<RedisTemplate<String, Object>> redis,
                                     ObjectProvider<CacheBroadcast> broadcast) {
        return new CacheService(cm, redis, broadcast);
    }

    /**
     * Periodic hit/miss stats logging (observability without Micrometer).
     * Interval configurable via {@code ep.cache.stats-log-interval-sec};
     * set to 0 (or negative) to disable. Daemon thread — never blocks shutdown.
     */
    @Bean(destroyMethod = "close")
    public CacheStatsLogger cacheStatsLogger(CacheService cacheService,
                                             @Value("${ep.cache.stats-log-interval-sec:60}") long intervalSec) {
        return new CacheStatsLogger(cacheService, intervalSec);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(RedisTemplate.class)
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf) {
        RedisTemplate<String, Object> t = new RedisTemplate<>();
        t.setConnectionFactory(cf);
        t.setKeySerializer(new StringRedisSerializer());
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        // 禁 DefaultTyping —— 跨语言纯 JSON 安全（D / ADR-T04）
        t.setValueSerializer(new GenericJackson2JsonRedisSerializer(om));
        t.afterPropertiesSet();
        return t;
    }

    @Bean
    @ConditionalOnProperty(name = "spring.data.redis.cluster.nodes")
    public LettuceConnectionFactory redisClusterConnectionFactory(
            @Value("${spring.data.redis.cluster.nodes}") String clusterNodes,
            @Value("${spring.data.redis.cluster.max-redirects:3}") int maxRedirects,
            @Value("${spring.data.redis.password:}") String password) {
        RedisClusterConfiguration config = new RedisClusterConfiguration(
                java.util.Arrays.stream(clusterNodes.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList());
        config.setMaxRedirects(maxRedirects);
        if (password != null && !password.isBlank()) {
            config.setPassword(org.springframework.data.redis.connection.RedisPassword.of(password));
        }
        return new LettuceConnectionFactory(config);
    }
}
