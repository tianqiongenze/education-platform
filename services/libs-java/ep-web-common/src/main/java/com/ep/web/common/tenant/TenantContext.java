package com.ep.web.common.tenant;

/**
 * Thread-local holder for the current tenant id.
 *
 * <p>Kept as a small, dependency-free utility so that the web filter
 * ({@link TenantContextFilter}), scheduled jobs, MyBatis-Plus tenant
 * interceptors, and tests can set the current tenant without depending on a
 * servlet request. Populated per request from the {@code X-Tenant-Id} header
 * (multi-tenant SaaS isolation — D-044 / D-133 / D-160 / D-161).
 *
 * <p>This is the canonical, shared implementation in {@code ep-web-common}.
 * It supersedes the byte-identical copies that were previously duplicated per
 * service ({@code com.ep.business.config.TenantContext},
 * {@code com.ep.grading.config.TenantContext},
 * {@code com.ep.order.config.TenantContext},
 * {@code com.ep.payment.config.TenantContext},
 * {@code com.ep.senior.config.TenantContext},
 * {@code com.ep.boardgame.config.TenantContext},
 * {@code com.ep.mcp.context.TenantContext},
 * {@code com.ep.knowledge.tenant.context.TenantContext}). The
 * {@code data-platform} service has its OWN {@code TenantContext} with
 * region-routing semantics ({@code setRegion/getRegion}) and is intentionally
 * NOT replaced by this class.
 *
 * <p>Thread-safety: backed by {@link InheritableThreadLocal}, so each request
 * thread (and each virtual thread borrowed by the servlet container) sees its
 * own value, and child threads spawned within a request inherit the parent's
 * tenant id (copy-on-create semantics). Callers MUST pair
 * {@link #setTenantId(String)} with {@link #clear()} in a {@code try/finally}
 * — see {@link TenantContextFilter} for the canonical request lifecycle.
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT_HOLDER = new InheritableThreadLocal<>();

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

    /**
     * Returns the current tenant id, falling back to {@link #DEFAULT_TENANT_ID}
     * when no tenant has been set.
     *
     * <p>Useful for data-access code that needs a non-null tenant id but does
     * not want to duplicate the defaulting logic. Note that
     * {@link TenantContextFilter} already defaults missing/blank headers to
     * {@link #DEFAULT_TENANT_ID}, so within a filtered request this method
     * simply returns whatever the filter set.
     *
     * @return the current tenant id, or {@link #DEFAULT_TENANT_ID} if none set
     */
    public static String getTenantIdOrDefault() {
        String tenantId = TENANT_HOLDER.get();
        return tenantId == null ? DEFAULT_TENANT_ID : tenantId;
    }

    public static void clear() {
        TENANT_HOLDER.remove();
    }
}
