package com.ep.knowledge.retrieval.cache;

import com.ep.cache.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HotQueryCacheWarmerTest {

    private CacheService cacheService;
    private StringRedisTemplate redis;
    private ObjectProvider<StringRedisTemplate> redisProvider;
    private HotQueryCacheWarmer warmer;

    @BeforeEach
    void setup() {
        cacheService = mock(CacheService.class);
        redis = mock(StringRedisTemplate.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        redisProvider = provider;

        warmer = new HotQueryCacheWarmer(redisProvider);
    }

    @Test
    void test_warm_puts_hot_queries_into_cache() {
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zops = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zops);

        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(tuple("如何学习Java", 9.0));
        tuples.add(tuple("Spring Boot 入门", 7.0));
        when(zops.reverseRangeWithScores("qa:hot:default", 0, 99)).thenReturn(tuples);

        warmer.warm(cacheService);

        verify(cacheService).put(eq("qa"), eq(HotQueryCacheWarmer.qHash("如何学习Java")), eq("如何学习Java"), eq(3600L));
        verify(cacheService).put(eq("qa"), eq(HotQueryCacheWarmer.qHash("Spring Boot 入门")), eq("Spring Boot 入门"), eq(3600L));
    }

    @Test
    void test_warm_handles_empty_hot_queries() {
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zops = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zops);
        when(zops.reverseRangeWithScores("qa:hot:default", 0, 99)).thenReturn(java.util.Collections.emptySet());

        assertDoesNotThrow(() -> warmer.warm(cacheService));

        verify(cacheService, never()).put(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void test_warm_handles_redis_unavailable() {
        when(redisProvider.getIfAvailable()).thenReturn(null);

        assertDoesNotThrow(() -> warmer.warm(cacheService));

        verifyNoInteractions(cacheService);
    }

    @Test
    void test_warm_handles_redis_exception() {
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zops = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zops);
        when(zops.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> warmer.warm(cacheService));

        verify(cacheService, never()).put(anyString(), anyString(), any(), anyLong());
    }

    private ZSetOperations.TypedTuple<String> tuple(String value, double score) {
        @SuppressWarnings("unchecked")
        ZSetOperations.TypedTuple<String> t = mock(ZSetOperations.TypedTuple.class);
        when(t.getValue()).thenReturn(value);
        when(t.getScore()).thenReturn(score);
        return t;
    }
}
