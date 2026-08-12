package com.ep.senior.config;

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
 * Unit tests for {@link TenantContextFilter} (D-161).
 *
 * <p>Mirrors the canonical filter-test pattern used in
 * {@code AuthFilterTest} (portal-bff): plain JUnit 5 + Spring's
 * {@link MockHttpServletRequest}/{@link MockHttpServletResponse}, invoking the
 * {@link jakarta.servlet.Filter} directly with a lambda chain. The
 * {@code TenantContextFilter} API matches {@code core-business} /
 * {@code grading-service} (D-133): extract {@code X-Tenant-Id}, fall back to
 * the default tenant when absent, and always clear the ThreadLocal in
 * {@code finally}.
 */
class TenantContextFilterTest {

    private TenantContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TenantContextFilter();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("valid X-Tenant-Id header is extracted and set on TenantContext")
    void testTenantHeaderExtracted() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/elder/profiles");
        req.addHeader(TenantContextFilter.TENANT_ID_HEADER, "tenant-42");
        MockHttpServletResponse res = new MockHttpServletResponse();

        final String[] seenDuringRequest = {null};
        FilterChain chain = (rq, rs) ->
                seenDuringRequest[0] = TenantContext.getTenantId();

        filter.doFilter(req, res, chain);

        assertEquals("tenant-42", seenDuringRequest[0],
                "TenantContext must hold the header value during the request");
    }

    @Test
    @DisplayName("missing X-Tenant-Id falls back to the default tenant (no 400/401)")
    void testMissingTenantHeaderFallsBackToDefault() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/elder/profiles");
        MockHttpServletResponse res = new MockHttpServletResponse();

        final String[] seenDuringRequest = {null};
        final boolean[] chainCalled = {false};
        FilterChain chain = (rq, rs) -> {
            chainCalled[0] = true;
            seenDuringRequest[0] = TenantContext.getTenantId();
        };

        filter.doFilter(req, res, chain);

        assertEquals(200, res.getStatus());
        assertEquals(true, chainCalled[0]);
        assertEquals(TenantContext.DEFAULT_TENANT_ID, seenDuringRequest[0],
                "Missing header must fall back to the default tenant id");
    }

    @Test
    @DisplayName("blank X-Tenant-Id also falls back to the default tenant")
    void testBlankTenantHeaderFallsBackToDefault() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/elder/profiles");
        req.addHeader(TenantContextFilter.TENANT_ID_HEADER, "   ");
        MockHttpServletResponse res = new MockHttpServletResponse();

        final String[] seenDuringRequest = {null};
        FilterChain chain = (rq, rs) ->
                seenDuringRequest[0] = TenantContext.getTenantId();

        filter.doFilter(req, res, chain);

        assertEquals(TenantContext.DEFAULT_TENANT_ID, seenDuringRequest[0]);
    }

    @Test
    @DisplayName("two sequential requests with different tenant ids do not leak")
    void testMultiTenantIsolation() throws Exception {
        MockHttpServletRequest reqA = new MockHttpServletRequest("GET", "/api/v1/elder/profiles");
        reqA.addHeader(TenantContextFilter.TENANT_ID_HEADER, "tenant-A");
        final String[] seenA = {null};
        filter.doFilter(reqA, new MockHttpServletResponse(),
                (rq, rs) -> seenA[0] = TenantContext.getTenantId());

        MockHttpServletRequest reqB = new MockHttpServletRequest("GET", "/api/v1/elder/profiles");
        reqB.addHeader(TenantContextFilter.TENANT_ID_HEADER, "tenant-B");
        final String[] seenB = {null};
        filter.doFilter(reqB, new MockHttpServletResponse(),
                (rq, rs) -> seenB[0] = TenantContext.getTenantId());

        assertEquals("tenant-A", seenA[0]);
        assertEquals("tenant-B", seenB[0],
                "Second request must see its own tenant, not the leaked first one");
    }

    @Test
    @DisplayName("TenantContext is cleared after the request completes (no ThreadLocal leak)")
    void testContextClearedAfterRequest() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/elder/profiles");
        req.addHeader(TenantContextFilter.TENANT_ID_HEADER, "tenant-99");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (rq, rs) -> {
            assertEquals("tenant-99", TenantContext.getTenantId());
        });

        assertNull(TenantContext.getTenantId(),
                "ThreadLocal must be cleared after the request finishes");
    }

    @Test
    @DisplayName("TenantContext is cleared even when the downstream chain throws")
    void testContextClearedWhenChainThrows() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/elder/profiles");
        req.addHeader(TenantContextFilter.TENANT_ID_HEADER, "tenant-err");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain chain = (rq, rs) -> {
            throw new RuntimeException("simulated downstream failure");
        };

        try {
            filter.doFilter(req, res, chain);
        } catch (RuntimeException expected) {
            // expected
        }

        assertNull(TenantContext.getTenantId(),
                "ThreadLocal must be cleared in finally even if the chain throws");
    }
}
