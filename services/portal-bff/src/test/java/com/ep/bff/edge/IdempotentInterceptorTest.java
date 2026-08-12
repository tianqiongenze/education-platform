package com.ep.bff.edge;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for IdempotentInterceptor (SDD §5.1, ADR-T06).
 */
class IdempotentInterceptorTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private IdempotentInterceptor interceptor;

    @BeforeEach
    void setup() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        interceptor = new IdempotentInterceptor(redisTemplate);
    }

    @Test
    @DisplayName("POST with Idempotency-Key passes when key is new")
    void postWithNewKeyPasses() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("Idempotency-Key", "uuid-123");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(valueOps.setIfAbsent(eq("idem:uuid-123"), eq("PENDING"), any()))
                .thenReturn(true);

        assertTrue(interceptor.preHandle(req, res, null));
        assertEquals(200, res.getStatus()); // no status set = default 200
    }

    @Test
    @DisplayName("POST with duplicate Idempotency-Key returns 409")
    void postWithDuplicateKeyReturns409() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("Idempotency-Key", "uuid-123");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(valueOps.setIfAbsent(eq("idem:uuid-123"), eq("PENDING"), any()))
                .thenReturn(false);
        when(valueOps.get("idem:uuid-123")).thenReturn("DONE:201");

        assertFalse(interceptor.preHandle(req, res, null));
        assertEquals(409, res.getStatus());
        assertTrue(res.getContentAsString().contains("duplicate"));
    }

    @Test
    @DisplayName("GET requests skip idempotency check")
    void getRequestsSkipCheck() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/123");
        req.addHeader("Idempotency-Key", "uuid-456");
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(req, res, null));
        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("POST without Idempotency-Key header passes through")
    void postWithoutKeyPassesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(req, res, null));
        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("afterCompletion removes key on exception (allows retry)")
    void afterCompletionRemovesKeyOnException() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("Idempotency-Key", "uuid-789");
        MockHttpServletResponse res = new MockHttpServletResponse();

        interceptor.afterCompletion(req, res, null, new RuntimeException("boom"));

        verify(redisTemplate).delete("idem:uuid-789");
    }

    @Test
    @DisplayName("afterCompletion marks key as DONE on success")
    void afterCompletionMarksDoneOnSuccess() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("Idempotency-Key", "uuid-999");
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(201);

        interceptor.afterCompletion(req, res, null, null);

        verify(valueOps).set(eq("idem:uuid-999"), eq("DONE:201"), any());
    }

    @Test
    @DisplayName("DELETE with Idempotency-Key is checked")
    void deleteIsChecked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("DELETE", "/api/v1/orders/123");
        req.addHeader("Idempotency-Key", "uuid-del-1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(valueOps.setIfAbsent(eq("idem:uuid-del-1"), eq("PENDING"), any()))
                .thenReturn(true);

        assertTrue(interceptor.preHandle(req, res, null));
    }
}
