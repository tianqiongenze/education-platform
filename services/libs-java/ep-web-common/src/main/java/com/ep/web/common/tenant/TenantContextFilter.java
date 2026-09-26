package com.ep.web.common.tenant;

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
 * <p>Security: enforces multi-tenant isolation for all services that pull in
 * {@code ep-web-common}. The ThreadLocal is read by data-access layers (JPA /
 * MyBatis-Plus tenant interceptors, Redis key prefixes, Pulsar topic routing)
 * to scope queries to the caller's tenant. Without this filter, a request
 * without an {@code X-Tenant-Id} header would silently run against the default
 * tenant (or, worse, a leaked previous tenant) — a cross-tenant
 * data-exposure bug.
 *
 * <p>Ordered at {@code HIGHEST_PRECEDENCE + 1} so it runs immediately after
 * any trace-id filter ({@code HIGHEST_PRECEDENCE}) and before any business
 * handler reads the tenant context. The {@code finally} block is critical:
 * Jetty reuses threads (and virtual threads can be borrowed across requests),
 * so failing to clear the ThreadLocal would leak the previous request's tenant
 * into the next one.
 *
 * <p>Registered automatically by {@link TenantWebAutoConfiguration} via a
 * {@code FilterRegistrationBean}. Services that previously declared this as a
 * {@code @Component} continue to work because the auto-configuration kicks in
 * with {@code ep.tenant.filter.enabled} defaulting to {@code true}; set that
 * property to {@code false} to disable auto-registration (e.g. for a service
 * that wants to register a custom tenant filter instead).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TenantContextFilter implements Filter {

    /** HTTP header carrying the tenant id for the current request. */
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
