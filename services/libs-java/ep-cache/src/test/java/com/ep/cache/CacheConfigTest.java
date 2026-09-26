package com.ep.cache;

import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
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
        CaffeineCacheManager cm = cfg.cacheManager(new CacheProperties());
        assertNotNull(cm);
        // Caffeine spec is set; requesting a cache yields a working Spring Cache.
        assertNotNull(cm.getCache("anything"));
    }

    @Test
    void defaultCaffeineSpecStaysBackwardCompatible() {
        CacheProperties props = new CacheProperties();
        assertEquals(10000, props.getDefaultMaximumSize(), "default maximumSize unchanged (10000)");
        assertEquals(60, props.getDefaultExpireAfterWriteSec(), "default expireAfterWrite unchanged (60s)");
        assertEquals(10000, props.resolveMaximumSize("unconfigured"));
        assertEquals(60, props.resolveExpireAfterWriteSec("unconfigured"));
    }

    @Test
    void cachesRecordStatsForObservability() {
        CacheConfig cfg = new CacheConfig();
        CaffeineCacheManager cm = cfg.cacheManager(new CacheProperties());

        CaffeineCache dynamic = (CaffeineCache) cm.getCache("whatever");
        assertNotNull(dynamic);
        assertTrue(dynamic.getNativeCache().policy().isRecordingStats(),
                "dynamically created caches must recordStats()");
    }

    @Test
    void perCacheNameSpecsAreRegisteredAndRecordStats() {
        CacheProperties props = new CacheProperties();
        CacheProperties.CacheSpec spec = new CacheProperties.CacheSpec();
        spec.setMaximumSize(5000L);
        spec.setExpireAfterWriteSec(120L);
        props.getSpecs().put("course", spec);
        props.getSpecs().put("bff:aggregation", new CacheProperties.CacheSpec()); // only defaults

        assertEquals(5000L, props.resolveMaximumSize("course"));
        assertEquals(120L, props.resolveExpireAfterWriteSec("course"));
        assertEquals(10000, props.resolveMaximumSize("bff:aggregation"), "empty spec falls back to defaults");

        CacheConfig cfg = new CacheConfig();
        CaffeineCacheManager cm = cfg.cacheManager(props);

        CaffeineCache course = (CaffeineCache) cm.getCache("course");
        assertNotNull(course, "cache with explicit spec is pre-registered");
        assertTrue(course.getNativeCache().policy().isRecordingStats(),
                "per-name caches must recordStats()");
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
