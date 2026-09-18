package com.ep.mcp.context;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Sets {@link TenantContext} from the {@code X-Tenant-Id} header for every
 * MCP request, and always clears the ThreadLocal afterwards.
 *
 * <p>Security (D-044): enforces multi-tenant isolation for all MCP tool calls.
 * Ordered at {@code 1} so it runs before any downstream handler reads the
 * tenant context.
 */
@Component
@Order(1)
public class TenantContextFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        String tenantId = httpReq.getHeader("X-Tenant-Id");
        if (tenantId != null && !tenantId.isBlank()) {
            TenantContext.setTenantId(tenantId);
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();  // Always clean up ThreadLocal
        }
    }
}
