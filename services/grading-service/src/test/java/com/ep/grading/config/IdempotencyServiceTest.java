package com.ep.grading.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdempotencyService}. Covers the Redis-backed claim
 * path, the fallback when Redis is unavailable, and the probe-only (null
 * valueId) path.
 */
class IdempotencyServiceTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<RedisTemplate<String, String>> provider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> template = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        when(template.opsForValue()).thenReturn(ops);
        service = new IdempotencyService(provider);
    }

    @Test
    void claimOrFetch_blankKey_returnsNull() {
        assertNull(service.claimOrFetch("", "val-1"));
        assertNull(service.claimOrFetch(null, "val-1"));
    }

    @Test
    void claimOrFetch_redisUnavailable_returnsNull() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertNull(service.claimOrFetch("key-1", "val-1"));
    }

    @Test
    void claimOrFetch_newKey_claimsAndReturnsNull() {
        when(provider.getIfAvailable()).thenReturn(template);
        when(ops.get("ep:idem:key-1")).thenReturn(null);
        when(ops.setIfAbsent(eq("ep:idem:key-1"), eq("val-1"), any(Duration.class)))
                .thenReturn(true);

        assertNull(service.claimOrFetch("key-1", "val-1"));

        verify(ops).setIfAbsent(eq("ep:idem:key-1"), eq("val-1"), eq(IdempotencyService.TTL));
    }

    @Test
    void claimOrFetch_existingKey_returnsStoredId() {
        when(provider.getIfAvailable()).thenReturn(template);
        when(ops.get("ep:idem:key-1")).thenReturn("existing-val");

        assertEquals("existing-val", service.claimOrFetch("key-1", "val-2"));

        // Should NOT call setIfAbsent when key already exists
        verify(ops, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void claimOrFetch_probeOnly_nullValueId_skipsSetIfAbsent() {
        when(provider.getIfAvailable()).thenReturn(template);
        when(ops.get("ep:idem:key-1")).thenReturn(null);

        assertNull(service.claimOrFetch("key-1", null));

        verify(ops, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void claimOrFetch_setIfAbsentLost_returnsCompetingId() {
        when(provider.getIfAvailable()).thenReturn(template);
        when(ops.get("ep:idem:key-1")).thenReturn(null);
        when(ops.setIfAbsent(eq("ep:idem:key-1"), eq("val-1"), any(Duration.class)))
                .thenReturn(false);
        when(ops.get("ep:idem:key-1")).thenReturn("competing-val");

        // First get returns null, setIfAbsent returns false, second get returns competing
        // But Mockito stubs return same value for same call, so we need to use thenReturn
        // with sequential returns
        when(ops.get("ep:idem:key-1"))
                .thenReturn(null)         // first check
                .thenReturn("competing-val"); // second check after setIfAbsent lost

        assertEquals("competing-val", service.claimOrFetch("key-1", "val-1"));
    }
}
