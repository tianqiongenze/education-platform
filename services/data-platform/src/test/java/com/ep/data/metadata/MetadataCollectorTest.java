package com.ep.data.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link MetadataCollector}. No Spring context, no
 * Pulsar/Iceberg/PostgreSQL connections.
 */
class MetadataCollectorTest {

    private final MetadataCollector collector = new MetadataCollector();

    @Test
    void test_parse_create_table_ddl() {
        String ddl = "CREATE TABLE users ("
                + "id VARCHAR(32) PRIMARY KEY, "
                + "name VARCHAR(128) NOT NULL, "
                + "email VARCHAR(256)"
                + ")";

        List<MetadataCollector.ColumnMetadata> cols =
                collector.collectFromDdlEvent("t1", "ods", "users", ddl);

        assertEquals(3, cols.size(), "should extract 3 columns");
        MetadataCollector.ColumnMetadata id = cols.get(0);
        assertEquals("id", id.columnName());
        assertTrue(id.dataType().toUpperCase().startsWith("VARCHAR"), "id type should be VARCHAR");
        assertEquals("ods", id.database());
        assertEquals("users", id.tableName());
        assertEquals("t1", id.tenantId());
        assertNotNull(id.collectedAt());

        assertEquals("name", cols.get(1).columnName());
        assertEquals("email", cols.get(2).columnName());
    }

    @Test
    void test_parse_ddl_with_nullable_columns() {
        String ddl = "CREATE TABLE t ("
                + "a INT NOT NULL, "
                + "b BIGINT, "
                + "c VARCHAR(64) NULL"
                + ")";

        List<MetadataCollector.ColumnMetadata> cols =
                collector.collectFromDdlEvent("t1", "ods", "t", ddl);

        assertEquals(3, cols.size());
        assertFalse(cols.get(0).nullable(), "NOT NULL column should be non-nullable");
        assertTrue(cols.get(1).nullable(), "column without NOT NULL should be nullable");
        assertTrue(cols.get(2).nullable(), "explicit NULL column should be nullable");
    }

    @Test
    void test_parse_ddl_skips_non_column_lines() {
        String ddl = "CREATE TABLE t ("
                + "id BIGINT NOT NULL, "
                + "name VARCHAR(128), "
                + "PRIMARY KEY (id), "
                + "INDEX idx_name (name), "
                + "CONSTRAINT chk CHECK (name <> ''), "
                + "KEY idx_id (id)"
                + ")";

        List<MetadataCollector.ColumnMetadata> cols =
                collector.collectFromDdlEvent("t1", "ods", "t", ddl);

        assertEquals(2, cols.size(), "PRIMARY KEY/INDEX/CONSTRAINT/KEY should be skipped");
        assertEquals("id", cols.get(0).columnName());
        assertEquals("name", cols.get(1).columnName());
    }

    @Test
    void test_store_logs_operation() {
        String ddl = "CREATE TABLE t (id BIGINT NOT NULL, name VARCHAR(64))";
        List<MetadataCollector.ColumnMetadata> cols =
                collector.collectFromDdlEvent("t1", "ods", "t", ddl);

        // Should not throw; in EP_DEV_ONLY (default) it just logs.
        collector.store(cols);
        collector.store(null);   // null tolerated
        collector.store(List.of()); // empty tolerated
    }

    @Test
    void test_empty_ddl_returns_empty_list() {
        assertTrue(collector.collectFromDdlEvent("t1", "ods", "t", "").isEmpty());
        assertTrue(collector.collectFromDdlEvent("t1", "ods", "t", "   ").isEmpty());
        assertTrue(collector.collectFromDdlEvent("t1", "ods", "t", null).isEmpty());
    }

    @Test
    void test_parse_ddl_with_comment() {
        String ddl = "CREATE TABLE t ("
                + "id BIGINT NOT NULL COMMENT '主键', "
                + "amount DECIMAL(18,4) COMMENT '金额'"
                + ")";

        List<MetadataCollector.ColumnMetadata> cols =
                collector.collectFromDdlEvent("t1", "ods", "t", ddl);

        assertEquals(2, cols.size());
        assertEquals("主键", cols.get(0).comment());
        assertEquals("金额", cols.get(1).comment());
        assertTrue(cols.get(0).dataType().startsWith("BIGINT"));
        assertTrue(cols.get(1).dataType().startsWith("DECIMAL"));
    }

    @Test
    void test_parse_ddl_with_backtick_identifiers() {
        String ddl = "CREATE TABLE `order` ("
                + "`id` BIGINT NOT NULL, "
                + "`status` INT NOT NULL"
                + ")";

        List<MetadataCollector.ColumnMetadata> cols =
                collector.collectFromDdlEvent("t1", "ods", "order", ddl);

        assertEquals(2, cols.size());
        assertEquals("id", cols.get(0).columnName());
        assertEquals("status", cols.get(1).columnName());
    }
}
