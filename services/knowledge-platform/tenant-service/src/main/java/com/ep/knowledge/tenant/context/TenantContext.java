package com.ep.knowledge.tenant.context;

/**
 * Thread-local holder for the current tenant id.
 *
 * <p>Kept as a small, dependency-free utility so that web filters, scheduled jobs,
 * and tests can set the current tenant without depending on a servlet request.
 * The {@link com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor}
 * reads from here via a {@link com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler}.
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
