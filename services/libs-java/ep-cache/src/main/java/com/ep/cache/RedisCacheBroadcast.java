package com.ep.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;

/**
 * Default {@link CacheBroadcast} backed by Redis Pub/Sub.
 * <p>
 * P1-5 (audit D-048): replaces the Pulsar-only broadcast so ep-cache no longer
 * hard-depends on an external message broker. Redis is already required as the
 * L2 cache store, so reusing its Pub/Sub channel removes a middleware without
 * losing cross-node eviction notifications.
 * <p>
 * Resilience: if {@link RedisTemplate} is unavailable the publish degrades to a
 * no-op, matching the previous Pulsar contract.
 */
public class RedisCacheBroadcast implements CacheBroadcast {
    private static final Logger log = LoggerFactory.getLogger(RedisCacheBroadcast.class);

    /** Redis channel name for cache-eviction notifications. */
    public static final String CHANNEL = "ep:cache:invalidate";

    private final ObjectProvider<RedisTemplate<String, Object>> redisProvider;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public RedisCacheBroadcast(ObjectProvider<RedisTemplate<String, Object>> redisProvider) {
        this.redisProvider = redisProvider;
    }

    @Override
    public void publishEvict(String cacheName, String key) {
        RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.debug("redis unavailable, skip broadcast for {}:{}", cacheName, key);
            return;
        }
        try {
            byte[] payload = mapper.writeValueAsBytes(new InvalidateMsg(cacheName, key));
            redis.convertAndSend(CHANNEL, payload);
        } catch (Exception e) {
            log.warn("redis broadcast fail for {}:{}: {}", cacheName, key, e.getMessage());
        }
    }

    /** Topic bean for {@link org.springframework.data.redis.listener.RedisMessageListenerContainer}. */
    public static ChannelTopic channelTopic() {
        return new ChannelTopic(CHANNEL);
    }
}
