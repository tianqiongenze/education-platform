package com.ep.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class CacheService {
    private static final Logger log = LoggerFactory.getLogger(CacheService.class);
    public static final String EMPTY_SENTINEL = "__EP_CACHE_EMPTY__";

    private final ObjectProvider<CacheManager> cmProvider;
    private final ObjectProvider<RedisTemplate<String, Object>> redisProvider;
    private final ObjectProvider<CacheBroadcast> broadcastProvider;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public CacheService(ObjectProvider<CacheManager> cm,
                        ObjectProvider<RedisTemplate<String, Object>> redis,
                        ObjectProvider<CacheBroadcast> b) {
        this.cmProvider = cm;
        this.redisProvider = redis;
        this.broadcastProvider = b;
    }

    public boolean isEnabled() {
        CacheManager cm = cmProvider.getIfAvailable();
        return cm != null;
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String cacheName, String key, Supplier<T> loader, long ttlSec, boolean cacheEmpty) {
        String fullKey = cacheName + ":" + key;
        // L1 Caffeine (via Spring CacheManager)
        CacheManager cm = cmProvider.getIfAvailable();
        if (cm != null) {
            Cache cache = cm.getCache(cacheName);
            if (cache != null) {
                Cache.ValueWrapper w = cache.get(key);
                if (w != null) return unwrap((T) w.get());
            }
        }
        // L2 Redis
        RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                Object raw = redis.opsForValue().get(fullKey);
                if (raw != null) {
                    if (EMPTY_SENTINEL.equals(raw)) return null;
                    return (T) raw;
                }
            } catch (Exception e) {
                log.warn("L2 redis miss/degrade: {}", e.getMessage());
            }
        }
        // loader
        T val = loader.get();
        if (val != null) {
            put(cacheName, key, val, ttlSec);
        } else if (cacheEmpty) {
            if (redis != null) {
                try {
                    redis.opsForValue().set(fullKey, EMPTY_SENTINEL, 30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("L2 empty-cache fail: {}", e.getMessage());
                }
            }
            if (cm != null) {
                Cache c = cm.getCache(cacheName);
                if (c != null) c.put(key, EMPTY_SENTINEL);
            }
        }
        return val;
    }

    public void put(String cacheName, String key, Object value, long ttlSec) {
        String fullKey = cacheName + ":" + key;
        RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                redis.opsForValue().set(fullKey, value, ttlSec, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("L2 put fail: {}", e.getMessage());
            }
        }
        CacheManager cm = cmProvider.getIfAvailable();
        if (cm != null) {
            Cache c = cm.getCache(cacheName);
            if (c != null) c.put(key, value);
        }
    }

    public void evict(String cacheName, String key) {
        String fullKey = cacheName + ":" + key;
        CacheManager cm = cmProvider.getIfAvailable();
        if (cm != null) {
            Cache c = cm.getCache(cacheName);
            if (c != null) c.evict(key);
        }
        RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                redis.delete(fullKey);
            } catch (Exception e) {
                log.warn("L2 delete fail: {}", e.getMessage());
            }
        }
        CacheBroadcast b = broadcastProvider.getIfAvailable();
        if (b != null) b.publishEvict(cacheName, key);
        // 延迟双删 100ms（M0：raw thread；生产用 scheduled executor）
        if (redis != null) {
            new Thread(() -> {
                try { Thread.sleep(100); redis.delete(fullKey); }
                catch (Exception e) { log.debug("double-delete skip"); }
            }).start();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T unwrap(T v) {
        return EMPTY_SENTINEL.equals(v) ? null : v;
    }
}
