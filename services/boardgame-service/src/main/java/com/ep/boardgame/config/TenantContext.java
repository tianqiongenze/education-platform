package com.ep.boardgame.config;

/**
 * Thread-local holder for the current tenant id.
 *
 * <p>Kept as a small, dependency-free utility so that the web filter
 * ({@link TenantContextFilter}), scheduled jobs, and tests can set the
 * current tenant without depending on a servlet request. Populated per
 * request from the {@code X-Tenant-Id} header (D-160 multi-tenant SaaS
 * isolation).
 *
 * <p>This mirrors {@code com.ep.business.config.TenantContext},
 * {@code com.ep.grading.config.TenantContext} and
 * {@code com.ep.mcp.context.TenantContext}; duplicated per service so
 * each deployment unit is self-contained for a ThreadLocal holder.
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT_HOLDER = new ThreadLocal<>();

    /** Default tenant used when the {@code X-Tenant-Id} header is absent. */
    public static final String DEFAULT_TENANT_ID = "default";

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
