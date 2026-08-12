package com.ep.data.common;

import java.util.Objects;

/**
 * Configuration for the Iceberg JDBC catalog used by Flink CDC sinks.
 *
 * <p>D-013/D-016/D-017: Flink→Iceberg lakehouse. C-03: checkpoint=commit.
 * Defaults target the platform's catalog Postgres + S3 warehouse.
 */
public final class IcebergCatalogConfig {

    private final String catalogType;
    private final String catalogUri;
    private final String warehouse;
    private final String jdbcDriver;
    private final String jdbcUser;
    private final String jdbcPassword;

    private IcebergCatalogConfig(Builder b) {
        this.catalogType = b.catalogType;
        this.catalogUri = b.catalogUri;
        this.warehouse = b.warehouse;
        this.jdbcDriver = b.jdbcDriver;
        this.jdbcUser = b.jdbcUser;
        this.jdbcPassword = b.jdbcPassword;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getCatalogType() { return catalogType; }
    public String getCatalogUri() { return catalogUri; }
    public String getWarehouse() { return warehouse; }
    public String getJdbcDriver() { return jdbcDriver; }
    public String getJdbcUser() { return jdbcUser; }
    public String getJdbcPassword() { return jdbcPassword; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IcebergCatalogConfig that)) return false;
        return Objects.equals(catalogType, that.catalogType)
                && Objects.equals(catalogUri, that.catalogUri)
                && Objects.equals(warehouse, that.warehouse)
                && Objects.equals(jdbcDriver, that.jdbcDriver)
                && Objects.equals(jdbcUser, that.jdbcUser)
                && Objects.equals(jdbcPassword, that.jdbcPassword);
    }

    @Override
    public int hashCode() {
        return Objects.hash(catalogType, catalogUri, warehouse, jdbcDriver, jdbcUser, jdbcPassword);
    }

    @Override
    public String toString() {
        return "IcebergCatalogConfig{catalogType='" + catalogType + "', catalogUri='" + catalogUri
                + "', warehouse='" + warehouse + "', jdbcDriver='" + jdbcDriver
                + "', jdbcUser='" + jdbcUser + "', jdbcPassword=***}";
    }

    /** Fluent builder for {@link IcebergCatalogConfig}. */
    public static final class Builder {
        private String catalogType = "jdbc";
        private String catalogUri = "jdbc:postgresql://catalog-pg:5432/iceberg";
        private String warehouse = "s3://edu-iceberg/warehouse";
        private String jdbcDriver = "org.postgresql.Driver";
        private String jdbcUser;
        private String jdbcPassword;

        private Builder() {
        }

        public Builder catalogType(String catalogType) { this.catalogType = catalogType; return this; }
        public Builder catalogUri(String catalogUri) { this.catalogUri = catalogUri; return this; }
        public Builder warehouse(String warehouse) { this.warehouse = warehouse; return this; }
        public Builder jdbcDriver(String jdbcDriver) { this.jdbcDriver = jdbcDriver; return this; }
        public Builder jdbcUser(String jdbcUser) { this.jdbcUser = jdbcUser; return this; }
        public Builder jdbcPassword(String jdbcPassword) { this.jdbcPassword = jdbcPassword; return this; }

        public IcebergCatalogConfig build() {
            return new IcebergCatalogConfig(this);
        }
    }
}
