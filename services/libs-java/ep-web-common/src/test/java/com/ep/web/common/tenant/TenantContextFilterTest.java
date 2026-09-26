package com.ep.web.common.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantContextFilter}. Verifies the canonical behavior
 * that was previously duplicated across the eight consuming services: the
 * {@code X-Tenant-Id} header is read into {@link TenantContext}, missing
 * headers default to {@link TenantContext#DEFAULT_TENANT_ID}, and the
 * ThreadLocal is always cleared after the request (no thread-pool leak).
 */
class TenantContextFilterTest {

    private final TenantContextFilter filter = new TenantContextFilter();

    @AfterEach
    void clearTenant() {
        // Defensive: no ThreadLocal should leak between tests.
        TenantContext.clear();
    }

    private HttpServletRequest requestWithHeader(String value) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(TenantContextFilter.TENANT_ID_HEADER)).thenReturn(value);
        return req;
    }

    @Test
    void headerPresent_setsTenantContext() throws Exception {
        HttpServletRequest req = requestWithHeader("acme-corp");
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> seen.set(TenantContext.getTenantId());

        filter.doFilter(req, mock(ServletResponse.class), chain);

        assertEquals("acme-corp", seen.get(),
                "TenantContext must hold the header value during the request");
        assertNull(TenantContext.getTenantId(),
                "TenantContext must be cleared after the request completes");
    }

    @Test
    void headerMissing_defaultsToDefaultTenantId() throws Exception {
        HttpServletRequest req = requestWithHeader(null);
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> seen.set(TenantContext.getTenantId());

        filter.doFilter(req, mock(ServletResponse.class), chain);

        assertEquals(TenantContext.DEFAULT_TENANT_ID, seen.get(),
                "Missing header must fall back to DEFAULT_TENANT_ID");
        assertNull(TenantContext.getTenantId(),
                "TenantContext must be cleared after the request completes");
    }

    @Test
    void headerBlank_defaultsToDefaultTenantId() throws Exception {
        HttpServletRequest req = requestWithHeader("  ");
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> seen.set(TenantContext.getTenantId());

        filter.doFilter(req, mock(ServletResponse.class), chain);

        assertEquals(TenantContext.DEFAULT_TENANT_ID, seen.get(),
                "Blank header must fall back to DEFAULT_TENANT_ID");
        assertNull(TenantContext.getTenantId(),
                "TenantContext must be cleared after the request completes");
    }

    @Test
    void afterRequest_tenantContextCleared() throws Exception {
        HttpServletRequest req = requestWithHeader("acme-corp");
        FilterChain chain = (rq, rs) -> { /* reads tenant mid-request */ };

        filter.doFilter(req, mock(ServletResponse.class), chain);

        // No thread leak: the ThreadLocal must be cleared after the request.
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void afterRequest_clearedEvenIfChainThrows() throws Exception {
        HttpServletRequest req = requestWithHeader("acme-corp");
        FilterChain chain = (rq, rs) -> {
            throw new RuntimeException("downstream boom");
        };

        assertThrows(RuntimeException.class,
                () -> filter.doFilter(req, mock(ServletResponse.class), chain));

        assertNull(TenantContext.getTenantId(),
                "ThreadLocal must be cleared even when the chain throws");
    }
}
