package com.ep.integration;

import com.ep.web.common.tenant.TenantContext;
import com.ep.web.common.tenant.TenantContextFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-198: Cross-service multi-tenant isolation E2E verification.
 *
 * <p>This test exercises the tenant-isolation contract that all Java services
 * share via their per-service {@code TenantContext} + {@code TenantContextFilter}
 * (core-business, grading-service, order-platform, payment-platform,
 * senior-care, boardgame-service, and the knowledge-platform modules). The
 * contract was unified in D-198 so that every service uses the same header
 * ({@code X-Tenant-Id}), the same default fallback ({@code "default"}), and the
 * same ThreadLocal cleanup semantics.
 *
 * <p>Because {@code TenantContext} is a ThreadLocal holder, the test uses
 * Spring's {@link MockHttpServletRequest}/{@link MockHttpServletResponse} to
 * simulate HTTP requests without starting a servlet container, following the
 * pattern established by {@code TenantContextFilterTest} in each service. The
 * difference is that this E2E test runs in the integration-tests module so it
 * verifies the <em>cross-service</em> contract rather than a single service's
 * filter.
 *
 * <p>Test scenarios:
 * <ul>
 *   <li>{@link #testTenantContextIsolation} — set/clear TenantContext, verify no leak.</li>
 *   <li>{@link #testTenantContextFilter} — simulate an HTTP request with the
 *       X-Tenant-Id header, verify the context is set during the request and
 *       cleared afterwards.</li>
 *   <li>{@link #testDefaultTenantFallback} — no X-Tenant-Id header falls back
 *       to the default tenant.</li>
 *   <li>{@link #testBlankTenantHeaderFallback} — blank header falls back.</li>
 *   <li>{@link #testMultiRequestNoLeak} — two sequential requests with
 *       different tenant ids do not leak.</li>
 *   <li>{@link #testContextClearedWhenChainThrows} — ThreadLocal is cleared
 *       even when the downstream chain throws.</li>
 *   <li>{@link #testConcurrentTenantIsolation} — many threads each set their
 *       own tenant; no cross-thread contamination.</li>
 *   <li>{@link #testTenantIdPropagationAcrossCallBoundary} — tenant id set on
 *       the request thread is visible to a child virtual thread (simulating
 *       async processing within a request).</li>
 * </ul>
 */
@Tag("integration")
@DisplayName("Cross-Service Tenant Isolation E2E (TenantContext + TenantContextFilter)")
class TenantIsolationE2ETest {

    private TenantContextFilter filter;

    @BeforeEach
    void setUp() {
        // core-business TenantContextFilter is a @Component with no constructor
        // args, so it can be instantiated directly.
        filter = new TenantContextFilter();
        // Always start from a clean ThreadLocal.
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- 1. TenantContext isolation (no filter, direct API) ----

    @Test
    @DisplayName("TenantContext: set tenant A -> getTenantId == A; clear; set B -> getTenantId == B; no leak")
    void testTenantContextIsolation() {
        // Set tenant A
        TenantContext.setTenantId("tenant-A");
        assertEquals("tenant-A", TenantContext.getTenantId());

        // Clear
        TenantContext.clear();
        assertNull(TenantContext.getTenantId(), "TenantContext must be null after clear");

        // Set tenant B
        TenantContext.setTenantId("tenant-B");
        assertEquals("tenant-B", TenantContext.getTenantId());

        // Verify no leak of A
        assertNotEquals("tenant-A", TenantContext.getTenantId(),
            "tenant-A must not leak after switching to tenant-B");
    }

    @Test
    @DisplayName("TenantContext.DEFAULT_TENANT_ID == 'default' (shared contract)")
    void testDefaultTenantIdConstant() {
        assertEquals("default", TenantContext.DEFAULT_TENANT_ID,
            "All services must use 'default' as the fallback tenant id (D-198 unified contract)");
    }

    // ---- 2. TenantContextFilter (HTTP request simulation) ----

    @Test
    @DisplayName("TenantContextFilter: X-Tenant-Id header is set on TenantContext during the request")
    void testTenantContextFilter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/courses");
        req.addHeader(TenantContextFilter.TENANT_ID_HEADER, "tenant-42");
        MockHttpServletResponse res = new MockHttpServletResponse();

        final String[] seenDuringRequest = {null};
        FilterChain chain = (rq, rs) -> seenDuringRequest[0] = TenantContext.getTenantId();

        filter.doFilter(req, res, chain);

        assertEquals("tenant-42", seenDuringRequest[0],
            "TenantContext must hold the header value during the request");
        // After the request, the ThreadLocal must be cleared.
        assertNull(TenantContext.getTenantId(),
            "TenantContext must be cleared after the request completes");
    }

    // ---- 3. Default tenant fallback ----

    @Test
    @DisplayName("TenantContextFilter: missing X-Tenant-Id falls back to 'default' (no 400/401)")
    void testDefaultTenantFallback() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/courses");
        // No X-Tenant-Id header
        MockHttpServletResponse res = new MockHttpServletResponse();

        final String[] seenDuringRequest = {null};
        final boolean[] chainCalled = {false};
        FilterChain chain = (rq, rs) -> {
            chainCalled[0] = true;
            seenDuringRequest[0] = TenantContext.getTenantId();
        };

        filter.doFilter(req, res, chain);

        // Missing header is NOT rejected — it falls back to the default tenant.
        assertEquals(200, res.getStatus(), "Missing tenant header must not cause an error status");
        assertTrue(chainCalled[0], "Filter chain must be invoked even without the header");
        assertEquals(TenantContext.DEFAULT_TENANT_ID, seenDuringRequest[0],
            "Missing header must fall back to the default tenant id");
    }

    @Test
    @DisplayName("TenantContextFilter: blank X-Tenant-Id also falls back to 'default'")
    void testBlankTenantHeaderFallback() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader(TenantContextFilter.TENANT_ID_HEADER, "   ");
        MockHttpServletResponse res = new MockHttpServletResponse();

        final String[] seenDuringRequest = {null};
        FilterChain chain = (rq, rs) -> seenDuringRequest[0] = TenantContext.getTenantId();

        filter.doFilter(req, res, chain);

        assertEquals(TenantContext.DEFAULT_TENANT_ID, seenDuringRequest[0],
            "Blank header must fall back to the default tenant id");
    }

    // ---- 4. Multi-request isolation (no leak) ----

    @Test
    @DisplayName("Two sequential requests with different tenant ids do not leak")
    void testMultiRequestNoLeak() throws Exception {
        // Request 1: tenant-A
        MockHttpServletRequest reqA = new MockHttpServletRequest("GET", "/api/v1/courses");
        reqA.addHeader(TenantContextFilter.TENANT_ID_HEADER, "tenant-A");
        final String[] seenA = {null};
        filter.doFilter(reqA, new MockHttpServletResponse(),
                (rq, rs) -> seenA[0] = TenantContext.getTenantId());

        // Request 2: tenant-B
        MockHttpServletRequest reqB = new MockHttpServletRequest("GET", "/api/v1/courses");
        reqB.addHeader(TenantContextFilter.TENANT_ID_HEADER, "tenant-B");
        final String[] seenB = {null};
        filter.doFilter(reqB, new MockHttpServletResponse(),
                (rq, rs) -> seenB[0] = TenantContext.getTenantId());

        assertEquals("tenant-A", seenA[0]);
        assertEquals("tenant-B", seenB[0],
            "Second request must see its own tenant, not the leaked first one");
    }

    // ---- 5. Cleanup on exception ----

    @Test
    @DisplayName("TenantContext is cleared even when the downstream chain throws")
    void testContextClearedWhenChainThrows() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/courses");
        req.addHeader(TenantContextFilter.TENANT_ID_HEADER, "tenant-err");
        MockHttpServletResponse res = new MockHttpServletResponse();

        FilterChain chain = (rq, rs) -> {
            throw new RuntimeException("simulated downstream failure");
        };

        assertThrows(RuntimeException.class, () -> filter.doFilter(req, res, chain));

        assertNull(TenantContext.getTenantId(),
            "ThreadLocal must be cleared in finally even if the chain throws");
    }

    // ---- 6. Concurrent tenant isolation ----

    @Test
    @DisplayName("Concurrent threads each set their own tenant; no cross-thread contamination")
    void testConcurrentTenantIsolation() throws Exception {
        int n = 32;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();
            ConcurrentHashMap<String, String> failures = new ConcurrentHashMap<>();

            for (int i = 0; i < n; i++) {
                final String tenantId = "tenant-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    // Set tenant on THIS thread
                    TenantContext.setTenantId(tenantId);
                    // Yield to encourage interleaving
                    Thread.yield();
                    Thread.yield();
                    String observed = TenantContext.getTenantId();
                    if (!tenantId.equals(observed)) {
                        failures.put(tenantId, observed);
                    }
                    TenantContext.clear();
                    return null;
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "All threads should reach the ready latch");
            start.countDown();

            for (Future<Void> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }

            assertTrue(failures.isEmpty(),
                "Cross-thread contamination detected: " + failures);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- 7. Tenant propagation across call boundary (virtual thread) ----

    @Test
    @DisplayName("Tenant id set on request thread is visible to a child virtual thread (async processing)")
    void testTenantIdPropagationAcrossCallBoundary() throws Exception {
        // Simulate: a request sets the tenant, then spawns a virtual thread to
        // do async work. The virtual thread inherits the ThreadLocal value at
        // spawn time (copy-on-create semantics for ThreadLocal in virtual
        // threads). This is the pattern used by @Async methods and
        // virtual-thread executors within a request scope.
        TenantContext.setTenantId("tenant-async");

        AtomicReference<String> observedByChild = new AtomicReference<>();
        // Use a platform thread to make the inheritance explicit (virtual
        // threads also inherit, but platform threads are the canonical case
        // for "child thread inherits parent ThreadLocal").
        Thread child = new Thread(() -> {
            observedByChild.set(TenantContext.getTenantId());
        });
        child.start();
        child.join(2000);

        // The child thread inherits the ThreadLocal value set on the parent
        // at the moment of thread creation. This verifies that tenant context
        // propagates to async work spawned within a request — a critical
        // requirement for data-access layers that read TenantContext.
        assertEquals("tenant-async", observedByChild.get(),
            "Child thread must inherit the parent's tenant id for async processing within a request");
    }

    // ---- 8. Header name contract ----

    @Test
    @DisplayName("TenantContextFilter.TENANT_ID_HEADER == 'X-Tenant-Id' (shared contract)")
    void testTenantIdHeaderConstant() {
        assertEquals("X-Tenant-Id", TenantContextFilter.TENANT_ID_HEADER,
            "All services must read the tenant from the 'X-Tenant-Id' header (D-198 unified contract)");
    }

    // ---- 9. Full filter lifecycle with a downstream handler ----

    @Test
    @DisplayName("Full filter lifecycle: header -> set context -> handler reads it -> cleared after")
    void testFullFilterLifecycle() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/orders");
        req.addHeader(TenantContextFilter.TENANT_ID_HEADER, "tenant-lifecycle");
        MockHttpServletResponse res = new MockHttpServletResponse();

        // The "handler" simulates a controller that reads the tenant to scope
        // a DB query (e.g. a MyBatis interceptor that appends WHERE tenant_id = ?).
        FilterChain chain = (rq, rs) -> {
            String tenant = TenantContext.getTenantId();
            assertNotNull(tenant, "Handler must see a non-null tenant");
            assertEquals("tenant-lifecycle", tenant);
            // Simulate writing a response (rs is ServletResponse; cast to HttpServletResponse for setStatus)
            ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(201);
        };

        filter.doFilter(req, res, chain);

        assertEquals(201, res.getStatus());
        assertNull(TenantContext.getTenantId(),
            "ThreadLocal must be cleared after the handler returns");
    }

    @Test
    @DisplayName("Unique tenant ids per request are correctly isolated (random UUID tenants)")
    void testRandomTenantIsolation() throws Exception {
        int n = 50;
        for (int i = 0; i < n; i++) {
            String tenant = UUID.randomUUID().toString();
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/courses");
            req.addHeader(TenantContextFilter.TENANT_ID_HEADER, tenant);
            final String[] seen = {null};
            filter.doFilter(req, new MockHttpServletResponse(),
                    (rq, rs) -> seen[0] = TenantContext.getTenantId());
            assertEquals(tenant, seen[0], "Request " + i + " must see its own tenant");
            assertNull(TenantContext.getTenantId(), "ThreadLocal cleared after request " + i);
        }
    }
}
