package com.ep.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * P1-5 (audit D-048): receives Redis Pub/Sub eviction messages from other nodes
 * and clears the corresponding entry from the local L1 (Caffeine) cache.
 * <p>
 * Iron rule satisfied: "禁止手写已存在中间件内置能力" - this is the receiving half
 * of Redis Pub/Sub, which Spring's {@code RedisMessageListenerContainer} drives;
 * this class only performs the local cache clear.
 */
public class CacheEvictionListener {
    private static final Logger log = LoggerFactory.getLogger(CacheEvictionListener.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ObjectProvider<CacheManager> cmProvider;

    public CacheEvictionListener(ObjectProvider<CacheManager> cmProvider) {
        this.cmProvider = cmProvider;
    }

    /**
     * Invoked by {@link org.springframework.data.redis.listener.adapter.MessageListenerAdapter}
     * with the raw message body. Parses the {@link InvalidateMsg} payload and
     * evicts only the local L1 entry (no re-broadcast, no L2 delete - the
     * publishing node already deleted L2).
     */
    public void onMessage(byte[] message) {
        try {
            InvalidateMsg msg = MAPPER.readValue(message, InvalidateMsg.class);
            CacheManager cm = cmProvider.getIfAvailable();
            if (cm == null || msg.getCacheName() == null) {
                return;
            }
            Cache cache = cm.getCache(msg.getCacheName());
            if (cache != null && msg.getKey() != null) {
                cache.evict(msg.getKey());
                log.debug("L1 evicted via broadcast {}:{}", msg.getCacheName(), msg.getKey());
            }
        } catch (Exception e) {
            log.warn("eviction-listener parse/clear fail: {}", e.getMessage());
        }
    }
}
