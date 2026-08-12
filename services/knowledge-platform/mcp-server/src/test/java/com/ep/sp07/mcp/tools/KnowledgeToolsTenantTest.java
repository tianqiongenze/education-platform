package com.ep.sp07.mcp.tools;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ep.sp07.mcp.context.TenantContext;
import com.ep.sp07.mcp.context.TenantContextFilter;
import com.ep.sp07.mcp.service.KnowledgeToolPort;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * D-044 + D-098 tests: verifies multi-tenant isolation via {@link TenantContext}
 * for both the generic {@link KnowledgeTools} (knowledge.search) and the
 * education-specific {@link EducationTools} (submission.grade, quiz.generate).
 *
 * <p>D-098: {@code gradeSubmission} and {@code generateQuiz} were moved from
 * {@link KnowledgeTools} to {@link EducationTools} so the core knowledge
 * platform is industry-agnostic. Tests for those methods now target
 * {@link EducationTools}.
 *
 * <p>Pure Mockito (no {@code @MockBean} / Spring context) so this stays a fast
 * unit test.
 */
class KnowledgeToolsTenantTest {

    private final KnowledgeToolPort service = mock(KnowledgeToolPort.class);
    private final KnowledgeTools tools = new KnowledgeTools(service);
    private final EducationTools eduTools = new EducationTools(service);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ─── KnowledgeTools: searchKnowledge ───

    @Test
    void searchKnowledge_usesTenantFromContext() {
        TenantContext.setTenantId("acme-tenant");
        when(service.searchKnowledge("calculus", "acme-tenant"))
                .thenReturn(Map.of("tool", "knowledge.search", "tenantId", "acme-tenant"));

        Map<String, Object> result = tools.searchKnowledge("calculus");

        assertEquals("acme-tenant", result.get("tenantId"));
        verify(service, times(1)).searchKnowledge("calculus", "acme-tenant");
    }

    @Test
    void searchKnowledge_noTenantContext_throws() {
        assertThrows(IllegalStateException.class, () -> tools.searchKnowledge("calculus"));
    }

    @Test
    void blankTenantContext_throws() {
        TenantContext.setTenantId("   ");
        assertThrows(IllegalStateException.class, () -> tools.searchKnowledge("calculus"));
    }

    // ─── EducationTools: gradeSubmission ───

    @Test
    void gradeSubmission_usesTenantFromContext() {
        TenantContext.setTenantId("tenant-7");
        when(service.gradeSubmission("sub-9", "tenant-7"))
                .thenReturn(Map.of("tool", "submission.grade", "tenantId", "tenant-7"));

        Map<String, Object> result = eduTools.gradeSubmission("sub-9");

        assertEquals("tenant-7", result.get("tenantId"));
        verify(service).gradeSubmission("sub-9", "tenant-7");
    }

    @Test
    void gradeSubmission_noTenantContext_throws() {
        assertThrows(IllegalStateException.class, () -> eduTools.gradeSubmission("sub-1"));
    }

    // ─── EducationTools: generateQuiz ───

    @Test
    void generateQuiz_usesTenantFromContext() {
        TenantContext.setTenantId("tenant-7");
        when(service.generateQuiz("algebra", 3, "tenant-7"))
                .thenReturn(Map.of("tool", "quiz.generate", "tenantId", "tenant-7"));

        Map<String, Object> result = eduTools.generateQuiz("algebra", null);

        assertEquals("tenant-7", result.get("tenantId"));
        verify(service).generateQuiz("algebra", 3, "tenant-7");
    }

    @Test
    void generateQuiz_noTenantContext_throws() {
        assertThrows(IllegalStateException.class, () -> eduTools.generateQuiz("algebra", 3));
    }

    // ─── TenantContextFilter ───

    @Test
    void filter_setsTenantFromHeader_andClearsAfterwards() throws Exception {
        TenantContextFilter filter = new TenantContextFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(req.getHeader("X-Tenant-Id")).thenReturn("header-tenant");

        TenantContext.clear();
        assertNull(TenantContext.getTenantId(), "precondition: no tenant set");

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertNull(TenantContext.getTenantId(),
                "ThreadLocal must be cleared in finally after the chain runs");
    }

    @Test
    void filter_makesTenantVisibleDuringChain() throws Exception {
        TenantContextFilter filter = new TenantContextFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);

        when(req.getHeader("X-Tenant-Id")).thenReturn("visible-tenant");

        // Capture the tenant id as seen from inside the chain
        String[] seen = new String[1];
        FilterChain chain = (request, response) -> seen[0] = TenantContext.getTenantId();

        filter.doFilter(req, res, chain);

        assertEquals("visible-tenant", seen[0]);
        assertNull(TenantContext.getTenantId(), "cleared after chain");
    }

    @Test
    void filter_missingHeader_doesNotSetTenant() throws Exception {
        TenantContextFilter filter = new TenantContextFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(req.getHeader("X-Tenant-Id")).thenReturn(null);
        TenantContext.clear();

        filter.doFilter(req, res, chain);

        assertNull(TenantContext.getTenantId());
        verify(chain).doFilter(req, res);
    }

    @Test
    void filter_blankHeader_doesNotSetTenant() throws Exception {
        TenantContextFilter filter = new TenantContextFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(req.getHeader("X-Tenant-Id")).thenReturn("  ");
        TenantContext.clear();

        filter.doFilter(req, res, chain);

        assertNull(TenantContext.getTenantId());
    }

    @Test
    void filter_clearsThreadLocalEvenIfChainThrows() throws Exception {
        TenantContextFilter filter = new TenantContextFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(req.getHeader("X-Tenant-Id")).thenReturn("boom-tenant");
        doThrow(new RuntimeException("downstream failure"))
                .when(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        assertThrows(RuntimeException.class, () -> filter.doFilter(req, res, chain));
        assertNull(TenantContext.getTenantId(),
                "ThreadLocal must be cleared even when the chain throws");
    }

    @Test
    void endToEnd_filterThenTool_seesCorrectTenant() throws Exception {
        // Simulate the request lifecycle: filter sets context, tool reads it, filter clears.
        TenantContextFilter filter = new TenantContextFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(req.getHeader("X-Tenant-Id")).thenReturn("e2e-tenant");
        when(service.searchKnowledge("calculus", "e2e-tenant"))
                .thenReturn(Map.of("tool", "knowledge.search", "tenantId", "e2e-tenant"));

        FilterChain chain = (request, response) -> {
            Map<String, Object> r = tools.searchKnowledge("calculus");
            assertEquals("e2e-tenant", r.get("tenantId"));
        };

        assertDoesNotThrow(() -> filter.doFilter(req, res, chain));
        assertNull(TenantContext.getTenantId());
        verify(service).searchKnowledge("calculus", "e2e-tenant");
    }
}
