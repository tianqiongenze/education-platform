package com.ep.bff.edge;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for IdempotencyFilter (SDD §5.1, ADR-T06; v7 D-233 response replay).
 */
class IdempotencyFilterTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private IdempotencyFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        filter = new IdempotencyFilter(redisTemplate);
    }

    private static String doneRecord(int status, String body) {
        return IdempotencyFilter.STATE_DONE + "|" + status + "|application/json|"
                + Base64.getEncoder().encodeToString(body.getBytes());
    }

    @Test
    @DisplayName("POST with Idempotency-Key proceeds when key is new (PENDING reserved)")
    void postWithNewKeyProceeds() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("Idempotency-Key", "uuid-123");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(valueOps.setIfAbsent(eq("idem:uuid-123"), eq(IdempotencyFilter.STATE_PENDING), any(Duration.class)))
                .thenReturn(true);

        filter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus());
        assertNotNull(chain.getRequest(), "chain should have been invoked");
    }

    @Test
    @DisplayName("duplicate after completion replays the cached response verbatim")
    void duplicateReplaysCachedResponse() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("Idempotency-Key", "uuid-123");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get("idem:uuid-123"))
                .thenReturn(doneRecord(201, "{\"id\":\"order-1\"}"));

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);

        assertEquals(201, res.getStatus(), "replayed status must match the original");
        assertEquals("{\"id\":\"order-1\"}", res.getContentAsString(), "replayed body must match the original");
        assertNull(chain.getRequest(), "downstream must not run for a replayed duplicate");
    }

    @Test
    @DisplayName("duplicate while still PENDING returns 409 with Retry-After")
    void duplicatePendingReturns409WithRetryAfter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("Idempotency-Key", "uuid-123");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get("idem:uuid-123")).thenReturn(IdempotencyFilter.STATE_PENDING);
        when(redisTemplate.getExpire("idem:uuid-123")).thenReturn(120L);

        filter.doFilter(req, res, new MockFilterChain());

        assertEquals(409, res.getStatus());
        assertEquals("120", res.getHeader("Retry-After"));
        assertTrue(res.getContentAsString().contains("being processed"));
    }

    @Test
    @DisplayName("GET requests skip idempotency check")
    void getRequestsSkipCheck() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/123");
        req.addHeader("Idempotency-Key", "uuid-456");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("POST without Idempotency-Key header passes through")
    void postWithoutKeyPassesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        verifyNoInteractions(valueOps);
    }

    @Test
    @DisplayName("chain exception releases the key so the client can retry")
    void chainExceptionReleasesKey() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("Idempotency-Key", "uuid-789");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        MockFilterChain failingChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws IOException, ServletException {
                throw new ServletException("boom");
            }
        };

        assertThrows(ServletException.class, () -> filter.doFilter(req, res, failingChain));
        verify(redisTemplate).delete("idem:uuid-789");
    }

    @Test
    @DisplayName("non-2xx response releases the key (corrected retry allowed)")
    void non2xxReleasesKey() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("Idempotency-Key", "uuid-400");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        MockFilterChain validationFail = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws java.io.IOException {
                // response is the ContentCachingResponseWrapper installed by the filter
                jakarta.servlet.http.HttpServletResponse httpResponse =
                        (jakarta.servlet.http.HttpServletResponse) response;
                httpResponse.setStatus(422);
            }
        };

        filter.doFilter(req, res, validationFail);

        verify(redisTemplate).delete("idem:uuid-400");
        assertEquals(422, res.getStatus());
    }

    @Test
    @DisplayName("PENDING reservation TTL is the short processing timeout, not 24h")
    void pendingTtlIsShort() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("Idempotency-Key", "uuid-ttl");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(valueOps.setIfAbsent(eq("idem:uuid-ttl"), eq(IdempotencyFilter.STATE_PENDING),
                eq(Duration.ofSeconds(IdempotencyFilter.PENDING_TTL_SECONDS)))).thenReturn(true);

        filter.doFilter(req, res, new MockFilterChain());

        verify(valueOps).setIfAbsent(eq("idem:uuid-ttl"), eq(IdempotencyFilter.STATE_PENDING),
                eq(Duration.ofSeconds(IdempotencyFilter.PENDING_TTL_SECONDS)));
        assertTrue(IdempotencyFilter.PENDING_TTL_SECONDS <= 300, "processing timeout must be minutes, not hours");
    }

    @Test
    @DisplayName("DELETE with Idempotency-Key is checked")
    void deleteIsChecked() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("DELETE", "/api/v1/orders/123");
        req.addHeader("Idempotency-Key", "uuid-del-1");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        filter.doFilter(req, res, new MockFilterChain());

        verify(valueOps).setIfAbsent(eq("idem:uuid-del-1"), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("corrupted DONE record falls back to a conservative 409")
    void corruptedRecordFallsBackTo409() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader("Idempotency-Key", "uuid-bad");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get("idem:uuid-bad")).thenReturn("DONE|garbage");

        filter.doFilter(req, res, new MockFilterChain());

        assertEquals(409, res.getStatus());
    }
}
