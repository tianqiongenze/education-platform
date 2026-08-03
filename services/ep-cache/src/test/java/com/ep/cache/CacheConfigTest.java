package com.ep.cache;

import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CacheConfig bean-factory tests. The bean methods are plain factory methods and can be
 * invoked directly without booting a Spring context. No real Redis/Pulsar is touched.
 */
class CacheConfigTest {

    @Test
    void cacheManagerIsCaffeineBacked() {
        CacheConfig cfg = new CacheConfig();
        CaffeineCacheManager cm = (CaffeineCacheManager) cfg.cacheManager();
        assertNotNull(cm);
        // Caffeine spec is set; requesting a cache yields a working Spring Cache.
        assertNotNull(cm.getCache("anything"));
    }

    @Test
    void redisTemplateWiresConnectionFactoryAndSerializers() {
        RedisConnectionFactory cf = mock(RedisConnectionFactory.class);
        CacheConfig cfg = new CacheConfig();
        RedisTemplate<String, Object> t = cfg.redisTemplate(cf);

        assertNotNull(t);
        assertSame(cf, t.getConnectionFactory());
        // Key serializer is a String serializer; value serializer is the JSON variant per ADR-T04.
        assertNotNull(t.getKeySerializer());
        assertNotNull(t.getValueSerializer());
        assertNotSame(t.getKeySerializer(), t.getValueSerializer());
    }
}
