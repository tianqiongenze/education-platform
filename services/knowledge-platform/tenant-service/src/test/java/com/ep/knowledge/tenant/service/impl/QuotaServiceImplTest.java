package com.ep.knowledge.tenant.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mockito-extension unit test for {@link QuotaServiceImpl}.
 *
 * <p>{@link QuotaServiceImpl} is a Redis-backed token-bucket rate limiter that
 * runs a Lua script to atomically decrement tokens. This test mocks the
 * {@link RedisTemplate} so the allow/deny logic and {@code remaining()} parsing
 * can be exercised without a Redis instance.
 */
@ExtendWith(MockitoExtension.class)
class QuotaServiceImplTest {

    @Mock
    @SuppressWarnings("unchecked")
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    @SuppressWarnings("unchecked")
    private ValueOperations<String, Object> valueOps;

    private QuotaServiceImpl quotaService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        quotaService = new QuotaServiceImpl(redisTemplate);
    }

    @Test
    void tryAcquire_returns_true_when_script_allows() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        assertTrue(quotaService.tryAcquire("tenant-1", 10));
    }

    @Test
    void tryAcquire_returns_false_when_script_denies() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(0L);

        assertFalse(quotaService.tryAcquire("tenant-1", 10));
    }

    @Test
    void tryAcquire_returns_false_when_script_returns_null() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(null);

        assertFalse(quotaService.tryAcquire("tenant-1", 1));
    }

    @Test
    void tryAcquire_uses_tenant_scoped_key_and_token_argument() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        quotaService.tryAcquire("tenant-1", 7);

        // The script key must be tenant-scoped, and the token count must be
        // forwarded as the script argument.
        verify(redisTemplate, times(1)).execute(
                any(RedisScript.class),
                argThat((List<String> keys) -> keys.contains("quota:tenant-1")),
                eq("7"));
    }

    @Test
    void remaining_returns_zero_when_key_absent() {
        when(valueOps.get("quota:tenant-1")).thenReturn(null);

        assertEquals(0L, quotaService.remaining("tenant-1"));
    }

    @Test
    void remaining_parses_long_value() {
        when(valueOps.get("quota:tenant-1")).thenReturn(123L);

        assertEquals(123L, quotaService.remaining("tenant-1"));
    }

    @Test
    void remaining_parses_string_numeric_value() {
        when(valueOps.get("quota:tenant-1")).thenReturn("456");

        assertEquals(456L, quotaService.remaining("tenant-1"));
    }

    @Test
    void remaining_returns_zero_for_non_numeric_value() {
        when(valueOps.get("quota:tenant-1")).thenReturn("not-a-number");

        assertEquals(0L, quotaService.remaining("tenant-1"));
    }
}
