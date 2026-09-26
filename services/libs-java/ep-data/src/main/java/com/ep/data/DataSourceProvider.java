package com.ep.data;

/**
 * D-216: Storage infrastructure every service may freely choose via
 * {@code ep.data.provider}. One enum — no per-service datasource boilerplate.
 *
 * <pre>
 * ep.data:
 *   enabled: true
 *   provider: cockroachdb        # tidb | cockroachdb | h2 | mysql
 *   write:
 *     url: jdbc:postgresql://crdb:26257/ep?sslmode=disable
 *   read:
 *     url: jdbc:postgresql://crdb-ro:26257/ep?sslmode=disable
 * </pre>
 */
public enum DataSourceProvider {

    /** TiDB — MySQL wire protocol, HTAP (TiKV + optional TiFlash replicas). */
    TIDB("com.mysql.cj.jdbc.Driver", "jdbc:mysql://%s/%s"),

    /** CockroachDB — PostgreSQL wire protocol, multi-region Raft. */
    COCKROACHDB("org.postgresql.Driver", "jdbc:postgresql://%s/%s?sslmode=disable"),

    /** MySQL / compatible (RDS, PolarDB ...). */
    MYSQL("com.mysql.cj.jdbc.Driver", "jdbc:mysql://%s/%s"),

    /** H2 in-memory — local dev / tests, single pool (read routes to write). */
    H2("org.h2.Driver", "jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1;MODE=MySQL");

    private final String driverClassName;
    private final String urlTemplate;

    DataSourceProvider(String driverClassName, String urlTemplate) {
        this.driverClassName = driverClassName;
        this.urlTemplate = urlTemplate;
    }

    public String driverClassName() {
        return driverClassName;
    }

    public String urlTemplate() {
        return urlTemplate;
    }

    /** True when the provider natively benefits from a separate read pool. */
    public boolean supportsReadReplicas() {
        return this == TIDB || this == COCKROACHDB || this == MYSQL;
    }
}
