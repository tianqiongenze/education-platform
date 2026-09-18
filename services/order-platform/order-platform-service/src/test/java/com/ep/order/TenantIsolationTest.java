package com.ep.order;

import com.ep.web.common.tenant.TenantContext;
import com.ep.web.common.tenant.TenantContextFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tenant-isolation tests for order-platform-service (D-209).
 *
 * <p>Verifies the three canonical multi-tenant behaviors required by the
 * scorecard P1 item "sp03-order/sp03-payment TenantContextFilter", exercising
 * the SHARED {@link com.ep.web.common.tenant.TenantContextFilter} that
 * ep-web-common auto-registers via {@code TenantWebAutoConfiguration}
 * (D-198 extraction). The previous per-service {@code @Component} copies in
 * {@code com.ep.order.config} were removed; this test asserts the shared
 * filter still provides the same guarantees for the order service.
 *
 * <p>Mirrors the unit-test pattern used by {@code core-business} /
 * {@code grading-service} / {@code senior-care} / {@code boardgame-service}
 * (D-133 / D-161): plain JUnit 5 + Spring's
 * {@link MockHttpServletRequest} / {@link MockHttpServletResponse}, invoking
 * the {@link jakarta.servlet.Filter} directly with a lambda chain.
 */
class TenantIsolationTest {

    private TenantContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TenantContextFilter();
        // Start each test from a clean ThreadLocal state.
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        // Defensive: no ThreadLocal should leak between tests.
        TenantContext.clear();
    }

    @Test
    @DisplayName("1. Request without X-Tenant-Id header falls back to the default tenant")
    void requestWithoutTenantHeader_getsDefaultTenant() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orders");
        MockHttpServletResponse res = new MockHttpServletResponse();

        final String[] seenDuringRequest = {null};
        final boolean[] chainCalled = {false};
        FilterChain chain = (rq, rs) -> {
            chainCalled[0] = true;
            seenDuringRequest[0] = TenantContext.getTenantId();
        };

        filter.doFilter(req, res, chain);

        // Per the D-133 contract: a missing header is NOT rejected with 400/401;
        // it falls back to the default tenant so downstream data-access code
        // never has to null-check the ThreadLocal.
        assertEquals(true, chainCalled[0], "chain must be invoked even without a tenant header");
        assertEquals(TenantContext.DEFAULT_TENANT_ID, seenDuringRequest[0],
                "Missing X-Tenant-Id must fall back to DEFAULT_TENANT_ID");
        assertNull(TenantContext.getTenantId(),
                "ThreadLocal must be cleared after the request completes");
    }

    @Test
    @DisplayName("2. Request with X-Tenant-Id header sets TenantContext correctly")
    void requestWithTenantHeader_setsTenantContextCorrectly() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader(TenantContextFilter.TENANT_ID_HEADER, "acme-corp");
        MockHttpServletResponse res = new MockHttpServletResponse();

        final String[] seenDuringRequest = {null};
        FilterChain chain = (rq, rs) ->
                seenDuringRequest[0] = TenantContext.getTenantId();

        filter.doFilter(req, res, chain);

        assertEquals("acme-corp", seenDuringRequest[0],
                "TenantContext must hold the X-Tenant-Id value during the request");
        assertNull(TenantContext.getTenantId(),
                "ThreadLocal must be cleared after the request completes");
    }

    @Test
    @DisplayName("3. Different tenant headers are isolated (no leakage between requests)")
    void differentTenantHeaders_areIsolated() throws Exception {
        // First request: tenant-A
        MockHttpServletRequest reqA = new MockHttpServletRequest("GET", "/api/v1/orders");
        reqA.addHeader(TenantContextFilter.TENANT_ID_HEADER, "tenant-A");
        final String[] seenA = {null};
        filter.doFilter(reqA, new MockHttpServletResponse(),
                (rq, rs) -> seenA[0] = TenantContext.getTenantId());

        // After reqA finishes, the ThreadLocal MUST be cleared so that the next
        // request on the same (pooled/virtual) thread does not see tenant-A.
        assertNull(TenantContext.getTenantId(),
                "ThreadLocal must be cleared between requests (no thread-pool leak)");

        // Second request: tenant-B — must see its own tenant, not the leaked tenant-A.
        MockHttpServletRequest reqB = new MockHttpServletRequest("GET", "/api/v1/orders");
        reqB.addHeader(TenantContextFilter.TENANT_ID_HEADER, "tenant-B");
        final String[] seenB = {null};
        filter.doFilter(reqB, new MockHttpServletResponse(),
                (rq, rs) -> seenB[0] = TenantContext.getTenantId());

        assertEquals("tenant-A", seenA[0],
                "First request must have seen tenant-A");
        assertEquals("tenant-B", seenB[0],
                "Second request must see tenant-B, not the leaked tenant-A");
        assertNull(TenantContext.getTenantId(),
                "ThreadLocal must be cleared after the final request");
    }
}
