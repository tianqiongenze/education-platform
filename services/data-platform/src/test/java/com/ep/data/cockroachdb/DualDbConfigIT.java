package com.ep.data.cockroachdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-202: Verifies {@link RoutingDataSource} routing logic WITHOUT needing
 * CockroachDB or Docker. Uses two H2 in-memory datasources — the "primary"
 * simulates TiDB (domestic), the "overseas" simulates CockroachDB — each
 * populated with a distinct table so we can detect which datasource a query
 * landed on.
 *
 * <p>This test always runs (no EP_LIVE guard, no Testcontainers). It guards the
 * core routing contract that {@link DualDbConfig} relies on: domestic tenants
 * hit primary, overseas tenants hit overseas, and an unset region defaults to
 * primary (fail-safe: never accidentally route domestic traffic overseas).
 */
class DualDbConfigIT {

    private static final String DOMESTIC_MARKER_TABLE = "tidb_marker";
    private static final String OVERSEAS_MARKER_TABLE = "crdb_marker";

    private DataSource primary;
    private DataSource overseas;
    private RoutingDataSource routing;

    @BeforeEach
    void setUp() throws Exception {
        primary = createH2("dualDbPrimary", DOMESTIC_MARKER_TABLE);
        overseas = createH2("dualDbOverseas", OVERSEAS_MARKER_TABLE);

        routing = new RoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(RoutingDataSource.KEY_PRIMARY, primary);
        targets.put(RoutingDataSource.KEY_OVERSEAS, overseas);
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(primary);
        // AbstractRoutingDataSource resolves the target map in afterPropertiesSet.
        routing.afterPropertiesSet();

        // Ensure a clean thread-local before each test.
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("region=domestic routes to primary (TiDB marker table exists)")
    void domesticRoutesToPrimary() {
        TenantContext.setRegion(TenantContext.REGION_DOMESTIC);
        assertEquals(RoutingDataSource.KEY_PRIMARY, RoutingDataSource.currentKey());

        JdbcTemplate jdbc = new JdbcTemplate(routing);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + DOMESTIC_MARKER_TABLE, Integer.class);
        assertEquals(1, count, "domestic routing should hit primary (TiDB) marker table");

        // The overseas-only table must NOT be visible through this routing.
        assertThrows(Exception.class, () -> jdbc.queryForObject(
                "SELECT count(*) FROM " + OVERSEAS_MARKER_TABLE, Integer.class),
                "domestic routing should NOT see the overseas (CRDB) marker table");
    }

    @Test
    @DisplayName("region=overseas routes to overseas (CockroachDB marker table exists)")
    void overseasRoutesToOverseas() {
        TenantContext.setRegion(TenantContext.REGION_OVERSEAS);
        assertEquals(RoutingDataSource.KEY_OVERSEAS, RoutingDataSource.currentKey());

        JdbcTemplate jdbc = new JdbcTemplate(routing);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + OVERSEAS_MARKER_TABLE, Integer.class);
        assertEquals(1, count, "overseas routing should hit overseas (CRDB) marker table");

        // The domestic-only table must NOT be visible through this routing.
        assertThrows(Exception.class, () -> jdbc.queryForObject(
                "SELECT count(*) FROM " + DOMESTIC_MARKER_TABLE, Integer.class),
                "overseas routing should NOT see the primary (TiDB) marker table");
    }

    @Test
    @DisplayName("no region set defaults to primary (fail-safe: never route domestic overseas)")
    void noRegionDefaultsToPrimary() {
        // TenantContext.getRegion() returns REGION_DOMESTIC when unset.
        assertEquals(RoutingDataSource.KEY_PRIMARY, RoutingDataSource.currentKey());

        JdbcTemplate jdbc = new JdbcTemplate(routing);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + DOMESTIC_MARKER_TABLE, Integer.class);
        assertEquals(1, count, "unset region should default to primary (TiDB)");
    }

    @Test
    @DisplayName("routing switches per-thread on region change")
    void routingSwitchesOnRegionChange() {
        JdbcTemplate jdbc = new JdbcTemplate(routing);

        TenantContext.setRegion(TenantContext.REGION_DOMESTIC);
        Integer domestic = jdbc.queryForObject(
                "SELECT count(*) FROM " + DOMESTIC_MARKER_TABLE, Integer.class);
        assertEquals(1, domestic);

        TenantContext.setRegion(TenantContext.REGION_OVERSEAS);
        Integer overseasCount = jdbc.queryForObject(
                "SELECT count(*) FROM " + OVERSEAS_MARKER_TABLE, Integer.class);
        assertEquals(1, overseasCount);

        TenantContext.clear();
        Integer backToDefault = jdbc.queryForObject(
                "SELECT count(*) FROM " + DOMESTIC_MARKER_TABLE, Integer.class);
        assertEquals(1, backToDefault, "after clear(), routing should default back to primary");
    }

    @Test
    @DisplayName("TenantContext isolates regions per thread")
    void tenantContextIsThreadLocal() throws Exception {
        TenantContext.setRegion(TenantContext.REGION_OVERSEAS);
        assertEquals(TenantContext.REGION_OVERSEAS, TenantContext.getRegion());

        StringBuilder otherThreadRegion = new StringBuilder();
        Thread t = new Thread(() -> {
            // A fresh thread should see the default (domestic), not "overseas".
            otherThreadRegion.append(TenantContext.getRegion());
        });
        t.start();
        t.join();

        assertEquals(TenantContext.REGION_DOMESTIC, otherThreadRegion.toString(),
                "TenantContext must be thread-local: a new thread should see domestic, not overseas");
        assertEquals(TenantContext.REGION_OVERSEAS, TenantContext.getRegion(),
                "Original thread should still see overseas");
    }

    /**
     * Creates an H2 in-memory datasource with a single marker table containing
     * exactly one row, so the table's presence/absence identifies which
     * datasource a routed query hit.
     */
    private DataSource createH2(String name, String markerTable) {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds.setUser("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS " + markerTable);
        jdbc.execute("CREATE TABLE " + markerTable + " (id INT PRIMARY KEY, src VARCHAR(32))");
        jdbc.update("INSERT INTO " + markerTable + " (id, src) VALUES (1, ?)", name);
        return ds;
    }
}
