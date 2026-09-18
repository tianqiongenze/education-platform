package com.ep.grading.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Sets {@link TenantContext} from the {@code X-Tenant-Id} header for every
 * request, and always clears the ThreadLocal afterwards.
 *
 * <p>Security (D-133): enforces multi-tenant isolation for grading-service,
 * closing the inconsistency where only mcp-server had a TenantContextFilter. The
 * ThreadLocal is read by data-access layers (JPA tenant interceptors, Redis
 * key prefixes) to scope grading/homework queries to the caller's tenant.
 *
 * <p>Ordered at {@code HIGHEST_PRECEDENCE + 1} so it runs immediately after
 * {@link TraceIdFilter} ({@code HIGHEST_PRECEDENCE}) and before any business
 * handler reads the tenant context. The {@code finally} block is critical:
 * Jetty reuses threads (and virtual threads can be borrowed across
 * requests), so failing to clear the ThreadLocal would leak the previous
 * request's tenant into the next one — a cross-tenant data-exposure bug.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TenantContextFilter implements Filter {

    public static final String TENANT_ID_HEADER = "X-Tenant-Id";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        String tenantId = httpReq.getHeader(TENANT_ID_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            // No header (e.g. internal/Dubbo calls): fall back to the default
            // tenant rather than leaving the ThreadLocal unset, so downstream
            // code never has to null-check TenantContext.getTenantId().
            tenantId = TenantContext.DEFAULT_TENANT_ID;
        }
        TenantContext.setTenantId(tenantId);
        try {
            chain.doFilter(req, res);
        } finally {
            // Always clean up ThreadLocal — prevents thread-pool contamination.
            TenantContext.clear();
        }
    }
}
