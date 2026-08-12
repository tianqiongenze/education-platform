package com.ep.data.cockroachdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DualDbConfigTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void routingDataSource_returnsOverseasKey_whenRegionOverseas() {
        TenantContext.setRegion(TenantContext.REGION_OVERSEAS);
        assertEquals(RoutingDataSource.KEY_OVERSEAS, RoutingDataSource.currentKey());
    }

    @Test
    void routingDataSource_returnsPrimaryKey_whenRegionDomestic() {
        TenantContext.setRegion(TenantContext.REGION_DOMESTIC);
        assertEquals(RoutingDataSource.KEY_PRIMARY, RoutingDataSource.currentKey());
    }

    @Test
    void routingDataSource_returnsPrimaryKey_whenRegionUnset() {
        // TenantContext defaults unset region to "domestic" → primary
        assertEquals(TenantContext.REGION_DOMESTIC, TenantContext.getRegion());
        assertEquals(RoutingDataSource.KEY_PRIMARY, RoutingDataSource.currentKey());
    }

    @Test
    void tenantContext_setAndClear() {
        TenantContext.setRegion(TenantContext.REGION_OVERSEAS);
        assertEquals(TenantContext.REGION_OVERSEAS, TenantContext.getRegion());
        TenantContext.clear();
        // After clear, getRegion defaults to domestic (not null)
        assertEquals(TenantContext.REGION_DOMESTIC, TenantContext.getRegion());
    }
}
