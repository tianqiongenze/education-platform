package com.ep.data.nlquery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link TrinoConfig} and {@link TrinoExecutor}.
 *
 * <p>No Spring context — both are plain POJOs instantiated with {@code new}.
 * The executor tests verify dev/CI mode (EP_DEV_ONLY active) where Trino
 * execution is skipped and empty results are returned.
 */
class TrinoConfigAndExecutorTest {

    // ─── TrinoConfig ──────────────────────────────────────────────────

    @Test
    void config_defaults_when_env_unset() {
        TrinoConfig config = TrinoConfig.fromEnv();
        assertNotNull(config, "fromEnv should never return null");
        // Defaults are applied when env vars are absent.
        assertEquals(TrinoConfig.DEFAULT_CATALOG, config.catalog(),
                "catalog should default to " + TrinoConfig.DEFAULT_CATALOG);
        assertEquals(TrinoConfig.DEFAULT_SCHEMA, config.schema(),
                "schema should default to " + TrinoConfig.DEFAULT_SCHEMA);
        assertEquals(TrinoConfig.DEFAULT_USER, config.user(),
                "user should default to " + TrinoConfig.DEFAULT_USER);
        assertEquals(TrinoConfig.DEFAULT_QUERY_TIMEOUT_SEC, config.queryTimeoutSec(),
                "timeout should default to " + TrinoConfig.DEFAULT_QUERY_TIMEOUT_SEC);
    }

    @Test
    void config_full_url_with_default_catalog_schema() {
        TrinoConfig config = new TrinoConfig(
                "jdbc:trino://localhost:8080",
                "iceberg", "default", "ep", null, 30);
        String full = config.fullUrl();
        assertTrue(full.contains("iceberg"), "fullUrl should contain catalog, got: " + full);
        assertTrue(full.contains("default"), "fullUrl should contain schema, got: " + full);
    }

    @Test
    void config_full_url_preserves_existing_path() {
        TrinoConfig config = new TrinoConfig(
                "jdbc:trino://host:8080/hive/warehouse",
                "hive", "warehouse", "u", null, 10);
        String full = config.fullUrl();
        assertNotNull(full, "fullUrl should not be null");
        // Should not double-append when path already present.
        assertTrue(full.startsWith("jdbc:trino://host:8080"), "got: " + full);
    }

    @Test
    void config_password_can_be_null() {
        TrinoConfig config = new TrinoConfig(
                "jdbc:trino://localhost:8080", "iceberg", "default", "u", null, 30);
        assertNull(config.password(), "password should be null when not set");
    }

    // ─── TrinoExecutor (dev/CI mode) ──────────────────────────────────

    @Test
    void executor_dev_mode_is_disabled() {
        TrinoExecutor executor = new TrinoExecutor();
        // EP_DEV_ONLY unset in CI → dev mode → disabled.
        assertFalse(executor.isEnabled(),
                "executor should be disabled in dev mode, got isEnabled=" + executor.isEnabled());
    }

    @Test
    void executor_dev_mode_returns_empty_result() {
        TrinoExecutor executor = new TrinoExecutor();
        TrinoQueryResult result = executor.execute("SELECT 1", 100);
        assertNotNull(result, "result should not be null");
        assertFalse(result.success(),
                "dev mode result should not be successful, got success=" + result.success());
        assertEquals(0, result.rowCount(),
                "dev mode result should have 0 rows, got " + result.rowCount());
    }

    @Test
    void executor_null_sql_returns_empty() {
        TrinoExecutor executor = new TrinoExecutor();
        TrinoQueryResult result = executor.execute(null, 100);
        assertFalse(result.success(), "null SQL should return unsuccessful result");
        assertNotNull(result.error(), "error message should be present for null SQL");
    }

    @Test
    void executor_blank_sql_returns_empty() {
        TrinoExecutor executor = new TrinoExecutor();
        TrinoQueryResult result = executor.execute("   ", 100);
        assertFalse(result.success(), "blank SQL should return unsuccessful result");
    }

    @Test
    void executor_prepared_dev_mode_returns_empty() {
        TrinoExecutor executor = new TrinoExecutor();
        TrinoQueryResult result = executor.executePrepared(
                "SELECT * FROM t WHERE id = ?", java.util.List.of(1), 100);
        assertNotNull(result, "prepared result should not be null");
        assertFalse(result.success(),
                "dev mode prepared result should not be successful");
    }

    // ─── TrinoQueryResult ─────────────────────────────────────────────

    @Test
    void query_result_empty_factory() {
        TrinoQueryResult result = TrinoQueryResult.empty("test error");
        assertFalse(result.success(), "empty result should not be successful");
        assertEquals(0, result.rowCount(), "empty result should have 0 rows");
        assertEquals("test error", result.error(), "error should match");
        assertTrue(result.columns().isEmpty(), "columns should be empty");
        assertTrue(result.rows().isEmpty(), "rows should be empty");
    }

    @Test
    void query_result_success_factory() {
        java.util.List<String> cols = java.util.List.of("id", "name");
        java.util.List<java.util.Map<String, Object>> rows = java.util.List.of(
                java.util.Map.of("id", 1, "name", "alice"));
        TrinoQueryResult result = new TrinoQueryResult(true, cols, rows, 1, null);
        assertTrue(result.success(), "result should be successful");
        assertEquals(2, result.columns().size(), "should have 2 columns");
        assertEquals(1, result.rowCount(), "should have 1 row");
        assertNull(result.error(), "error should be null for success");
    }
}
