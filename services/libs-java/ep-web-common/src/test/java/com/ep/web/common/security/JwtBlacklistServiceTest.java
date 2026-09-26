package com.ep.web.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * D-227: {@link JwtBlacklistService} unit tests (mocked Redis). Covers the
 * write path with TTL propagation, the fail-open availability contract, and
 * the no-op mode for Redis-less consumers.
 */
class JwtBlacklistServiceTest {

    private static final String KEY = JwtBlacklistService.KEY_PREFIX + "jti-123";

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);

    private JwtBlacklistService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        service = new JwtBlacklistService(redis);
    }

    @Test
    @DisplayName("revoke stores the jti under the blacklist key with the remaining TTL")
    void revokeWritesKeyWithTtl() {
        assertTrue(service.revoke("jti-123", Duration.ofSeconds(90)));
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(org.mockito.ArgumentMatchers.eq(KEY),
                org.mockito.ArgumentMatchers.eq("1"), ttl.capture());
        assertEquals(Duration.ofSeconds(90), ttl.getValue(), "TTL must equal the token's remaining validity");
    }

    @Test
    @DisplayName("revoke with a non-positive TTL is a no-op success (token already expired)")
    void revokeExpiredTokenNeedsNoWrite() {
        assertTrue(service.revoke("jti-123", Duration.ZERO));
        assertTrue(service.revoke("jti-123", Duration.ofSeconds(-5)));
        assertTrue(service.revoke("jti-123", null));
        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("isRevoked returns true only while the blacklist entry exists")
    void isRevokedReflectsEntry() {
        when(valueOps.get(KEY)).thenReturn("1");
        assertTrue(service.isRevoked("jti-123"));

        when(valueOps.get(KEY)).thenReturn(null);
        assertFalse(service.isRevoked("jti-123"), "absent entry means not revoked");
    }

    @Test
    @DisplayName("Redis outage: isRevoked fails OPEN (false), revoke reports failure — no exception escapes")
    void redisOutageFailsOpen() {
        when(valueOps.get(KEY)).thenThrow(new RedisConnectionFailureException("connection refused"));
        assertFalse(service.isRevoked("jti-123"), "availability first: blacklist check must fail open");

        doThrow(new RedisConnectionFailureException("connection refused")).when(valueOps)
                .set(org.mockito.ArgumentMatchers.eq(KEY), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(Duration.class));
        assertFalse(service.revoke("jti-123", Duration.ofSeconds(60)),
                "revoke must report that the token stays valid");
    }

    @Test
    @DisplayName("any unexpected Redis error also fails open")
    void unexpectedErrorFailsOpen() {
        when(valueOps.get(KEY)).thenThrow(new IllegalStateException("serialization blew up"));
        assertFalse(service.isRevoked("jti-123"));
    }

    @Test
    @DisplayName("blank jti is rejected without touching Redis")
    void blankJtiIgnored() {
        assertFalse(service.revoke(" ", Duration.ofSeconds(60)));
        assertFalse(service.revoke(null, Duration.ofSeconds(60)));
        assertFalse(service.isRevoked(null));
        assertFalse(service.isRevoked(""));
        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("no-Redis mode (null template) never revokes and never reports revoked")
    void nullRedisIsNoOp() {
        JwtBlacklistService noRedis = new JwtBlacklistService(null);
        assertFalse(noRedis.isRevoked("jti-123"));
        assertFalse(noRedis.revoke("jti-123", Duration.ofSeconds(60)));
    }
}
