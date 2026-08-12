package com.ep.data.cockroachdb;

/**
 * D-194: Thread-local tenant context used by {@link RoutingDataSource} to
 * pick the correct datasource (domestic TiDB vs overseas CockroachDB).
 * <p>
 * Populated per request from the {@code X-Tenant-Region} header
 * ({@code domestic} or {@code overseas}). Mirrors the per-service
 * TenantContext pattern used elsewhere in the platform.
 */
public final class TenantContext {

    /** Region value indicating a domestic (mainland) tenant — routes to TiDB. */
    public static final String REGION_DOMESTIC = "domestic";
    /** Region value indicating an overseas tenant — routes to CockroachDB. */
    public static final String REGION_OVERSEAS = "overseas";

    private static final ThreadLocal<String> REGION_HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void setRegion(String region) {
        REGION_HOLDER.set(region);
    }

    public static String getRegion() {
        String r = REGION_HOLDER.get();
        return r == null ? REGION_DOMESTIC : r;
    }

    public static void clear() {
        REGION_HOLDER.remove();
    }
}
