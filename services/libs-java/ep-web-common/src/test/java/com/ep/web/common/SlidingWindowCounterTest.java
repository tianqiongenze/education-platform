package com.ep.web.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * v7 (D-233) tests for the sliding-window risk-control stack:
 * RedisSlidingWindowCounter (half-open self-healing) and
 * RiskControlFilter.MemorySlidingWindowCounter (trailing-window counting).
 */
class SlidingWindowCounterTest {

    @Test
    @DisplayName("memory sliding window counts within the trailing window")
    void memorySlidingWindowCountsTrailingWindow() {
        AtomicLong now = new AtomicLong(0);
        var counter = new RiskControlFilter.MemorySlidingWindowCounter(new java.time.Clock() {
            @Override
            public long millis() {
                return now.get();
            }

            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.Instant instant() {
                return java.time.Instant.ofEpochMilli(now.get());
            }
        });

        String key = "ep:risk:req:ip:1.2.3.4";

        // 3 hits at t=0
        assertEquals(1, counter.increment(key, 60));
        assertEquals(2, counter.increment(key, 60));
        assertEquals(3, counter.increment(key, 60));

        // at t=30s still 4 (within trailing 60s)
        now.set(30_000);
        assertEquals(4, counter.increment(key, 60));

        // at t=61s the trailing 60s window is [1s, 61s]: the t=0 hits fell
        // out, but the t=30s hit is still inside → 1 (new) + 1 (t=30s) = 2
        now.set(61_000);
        assertEquals(2, counter.increment(key, 60));
    }

    @Test
    @DisplayName("memory sliding window eliminates the fixed-window boundary burst")
    void noBoundaryBurst() {
        AtomicLong now = new AtomicLong(59_900);
        var counter = new RiskControlFilter.MemorySlidingWindowCounter(new java.time.Clock() {
            @Override
            public long millis() {
                return now.get();
            }

            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.Instant instant() {
                return java.time.Instant.ofEpochMilli(now.get());
            }
        });

        String key = "ep:risk:req:ip:5.6.7.8";
        int limit = 5;

        // Exhaust the budget just before the fixed-window boundary (t≈59.9s)
        for (int i = 0; i < limit; i++) {
            counter.increment(key, 60);
        }
        // Fixed window would reset at t=60s and allow 5 more within 100ms —
        // the sliding window must NOT: the trailing 60s span still holds 5 hits.
        now.set(60_100);
        assertEquals(6, counter.increment(key, 60), "boundary burst must be counted, not reset");
        assertEquals(7, counter.increment(key, 60));
    }

    @Test
    @DisplayName("Redis counter uses ZSET ops and reports cluster-wide counts")
    void redisSlidingWindowUsesZset() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSet = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zSet);
        when(zSet.zCard(anyString())).thenReturn(7L);

        RedisSlidingWindowCounter counter = new RedisSlidingWindowCounter(redis);

        assertEquals(7, counter.increment("ep:risk:req:ip:1.1.1.1", 60));
        verify(zSet).add(anyString(), anyString(), anyDouble());
        verify(zSet).removeRangeByScore(anyString(), anyDouble(), anyDouble());
        verify(redis).expire(anyString(), any(Duration.class));
        assertFalse(counter.isDegraded());
    }

    @Test
    @DisplayName("Redis failure degrades to in-memory and half-open re-probes after cooldown")
    void halfOpenSelfHealing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSet = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zSet);
        when(zSet.add(anyString(), anyString(), anyDouble()))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(true); // recovers on the post-cooldown half-open probe
        when(zSet.zCard(anyString())).thenReturn(3L);

        AtomicLong nanos = new AtomicLong(0);
        RedisSlidingWindowCounter counter = new RedisSlidingWindowCounter(redis, nanos::get);

        // First call: Redis down → degrade to in-memory (returns 1 from fallback)
        assertEquals(1, counter.increment("k", 60));
        assertTrue(counter.isDegraded());

        // Within cooldown: still degraded, no Redis probe
        nanos.set(RedisSlidingWindowCounter.RECOVERY_COOLDOWN_MS * 1_000_000L / 2);
        assertEquals(2, counter.increment("k", 60));
        assertTrue(counter.isDegraded());
        verify(zSet, times(1)).add(anyString(), anyString(), anyDouble());

        // After cooldown: half-open probe succeeds → cluster-wide restored
        nanos.set(RedisSlidingWindowCounter.RECOVERY_COOLDOWN_MS * 1_000_000L + 1);
        assertEquals(3, counter.increment("k", 60));
        assertFalse(counter.isDegraded(), "half-open probe must restore the Redis path");
        verify(zSet, times(2)).add(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("clientIp ignores XFF when trustedProxies=0 (anti-spoof default)")
    void clientIpIgnoresXffByDefault() {
        org.springframework.mock.web.MockHttpServletRequest mockReq =
                new org.springframework.mock.web.MockHttpServletRequest();
        mockReq.setRemoteAddr("203.0.113.9");
        mockReq.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.1");
        assertEquals("203.0.113.9",
                RiskControlFilter.clientIp(mockReq, 0),
                "spoofable XFF must be ignored with zero trusted proxies");
    }

    @Test
    @DisplayName("clientIp resolves the N+1-th entry from the right when proxies are trusted")
    void clientIpTrustedProxyChain() {
        org.springframework.mock.web.MockHttpServletRequest mockReq =
                new org.springframework.mock.web.MockHttpServletRequest();
        mockReq.setRemoteAddr("10.0.0.254");
        mockReq.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.1");
        // 1 trusted proxy → client IP is the right-most non-proxy entry
        assertEquals("10.0.0.1", RiskControlFilter.clientIp(mockReq, 1));
        // 2 trusted proxies → the real client at the chain's left end
        assertEquals("198.51.100.7", RiskControlFilter.clientIp(mockReq, 2));
        // more trusted proxies than chain length → defensive socket fallback
        assertEquals("10.0.0.254", RiskControlFilter.clientIp(mockReq, 5));
    }
}
