package com.ep.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RedisCacheBroadcast tests. Mirrors the resilience guarantees previously held
 * by the Pulsar-backed CacheBroadcast: no Redis → no-op; Redis available →
 * convertAndSend invoked; exceptions swallowed.
 */
class CacheBroadcastTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishEvictIsNoOpWhenRedisUnavailable() {
        ObjectProvider<RedisTemplate<String, Object>> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);

        CacheBroadcast b = new RedisCacheBroadcast(p);
        assertDoesNotThrow(() -> b.publishEvict("course:detail", "c-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishEvictConvertsAndSendsWhenRedisAvailable() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ObjectProvider<RedisTemplate<String, Object>> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(redis);

        CacheBroadcast b = new RedisCacheBroadcast(p);
        b.publishEvict("course:detail", "c-2");

        verify(redis).convertAndSend(eq(RedisCacheBroadcast.CHANNEL), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishEvictToleratesRedisException() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        when(redis.convertAndSend(anyString(), any())).thenThrow(new RuntimeException("redis down"));
        ObjectProvider<RedisTemplate<String, Object>> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(redis);

        CacheBroadcast b = new RedisCacheBroadcast(p);
        assertDoesNotThrow(() -> b.publishEvict("course:detail", "c-3"));
    }

    @Test
    void channelConstantStable() {
        assertEquals("ep:cache:invalidate", RedisCacheBroadcast.CHANNEL);
    }
}
