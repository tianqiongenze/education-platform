package com.ep.mcp.context;

/**
 * Thread-local holder for the current tenant id.
 *
 * <p>Kept as a small, dependency-free utility so that the web filter
 * ({@link TenantContextFilter}), scheduled jobs, and tests can set the
 * current tenant without depending on a servlet request. Populated per
 * request from the {@code X-Tenant-Id} header (D-044 multi-tenant SaaS
 * isolation).
 *
 * <p>Mirrors the {@code com.ep.knowledge.tenant.context.TenantContext}
 * used by tenant-service; duplicated here so mcp-server does
 * not need a cross-module dependency for a ThreadLocal holder.
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT_HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(String tenantId) {
        TENANT_HOLDER.set(tenantId);
    }

    public static String getTenantId() {
        return TENANT_HOLDER.get();
    }

    public static void clear() {
        TENANT_HOLDER.remove();
    }
}
