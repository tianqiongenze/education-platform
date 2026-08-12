package com.ep.data.cockroachdb;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;

/**
 * D-194: Routes JDBC connections to the primary (TiDB) or overseas
 * (CockroachDB) datasource based on {@link TenantContext#getRegion()}.
 * <p>
 * Registered by {@link DualDbConfig} only when {@code ep.db.dual.enabled=true}.
 * Domestic tenants → "primary"; overseas tenants → "overseas".
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    public static final String KEY_PRIMARY = "primary";
    public static final String KEY_OVERSEAS = "overseas";

    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.REGION_OVERSEAS.equals(TenantContext.getRegion())
                ? KEY_OVERSEAS : KEY_PRIMARY;
    }

    /**
     * Convenience accessor for tests: returns the lookup key the routing
     * datasource would use given the current TenantContext.
     */
    public static String currentKey() {
        return TenantContext.REGION_OVERSEAS.equals(TenantContext.getRegion())
                ? KEY_OVERSEAS : KEY_PRIMARY;
    }
}
