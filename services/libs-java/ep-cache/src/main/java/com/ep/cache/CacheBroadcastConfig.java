package com.ep.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * P1-5 (audit D-048): wires the cache-broadcast backend.
 * <p>
 * Selection via {@code ep.cache.broadcast}:
 * <ul>
 *   <li>{@code redis} (default) - {@link RedisCacheBroadcast} + Redis Pub/Sub listener</li>
 *   <li>{@code pulsar} - {@link PulsarCacheBroadcast}, Pulsar must be configured separately</li>
 * </ul>
 * Iron rule satisfied: "禁止手写已存在中间件内置能力" - we use Redis Pub/Sub and
 * Spring's RedisMessageListenerContainer rather than hand-rolling a broker.
 */
@Configuration
public class CacheBroadcastConfig {
    private static final Logger log = LoggerFactory.getLogger(CacheBroadcastConfig.class);

    /**
     * Default broadcast backend: Redis Pub/Sub.
     * Active unless {@code ep.cache.broadcast=pulsar} is set.
     */
    @Bean
    @ConditionalOnProperty(name = "ep.cache.broadcast", havingValue = "redis", matchIfMissing = true)
    public CacheBroadcast redisCacheBroadcast(
            ObjectProvider<RedisTemplate<String, Object>> redisProvider) {
        log.info("CacheBroadcast backend: redis (Pub/Sub channel={})", RedisCacheBroadcast.CHANNEL);
        return new RedisCacheBroadcast(redisProvider);
    }

    /**
     * Optional Pulsar broadcast backend, active only when explicitly requested.
     */
    @Bean
    @ConditionalOnProperty(name = "ep.cache.broadcast", havingValue = "pulsar")
    public CacheBroadcast pulsarCacheBroadcast(
            ObjectProvider<org.apache.pulsar.client.api.PulsarClient> clientProvider,
            @Value("${ep.cache.pulsar.topic:ep-cache-invalidate}") String topic) {
        log.info("CacheBroadcast backend: pulsar (topic={})", topic);
        return new PulsarCacheBroadcast(clientProvider, topic);
    }

    /**
     * Redis Pub/Sub listener container. Receives eviction notifications from
     * other nodes and delegates to {@link CacheEvictionListener} which clears
     * the local L1 (Caffeine) cache. Only active for the Redis broadcast mode.
     * v8 (D-243): the L3 hotspot mirror is wired into the listener so broadcast
     * evictions clear it too.
     */
    @Bean
    @ConditionalOnProperty(name = "ep.cache.broadcast", havingValue = "redis", matchIfMissing = true)
    public RedisMessageListenerContainer cacheEvictionListenerContainer(
            ObjectProvider<RedisConnectionFactory> cfProvider,
            ObjectProvider<org.springframework.cache.CacheManager> cmProvider,
            ObjectProvider<CacheService> cacheServiceProvider) {
        RedisConnectionFactory cf = cfProvider.getIfAvailable();
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        if (cf != null) {
            container.setConnectionFactory(cf);
            CacheService cacheService = cacheServiceProvider.getIfAvailable();
            CacheEvictionListener listener = new CacheEvictionListener(cmProvider,
                    cacheService != null ? cacheService.getHotspotMirror() : null);
            container.addMessageListener(
                    new MessageListenerAdapter(listener, "onMessage"),
                    RedisCacheBroadcast.channelTopic());
        } else {
            log.warn("RedisConnectionFactory unavailable; eviction listener container is inert");
        }
        return container;
    }
}
