package com.ep.data.nlquery;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Executes validated Trino SQL queries and returns results (D-190).
 *
 * <p><b>EP_DEV_ONLY guard:</b> When {@code EP_DEV_ONLY} is unset, blank,
 * or {@code "true"}, the executor is disabled and {@link #execute} returns
 * an empty result. Production deployments set {@code EP_DEV_ONLY=false}
 * and provide a reachable Trino coordinator.
 *
 * <p><b>Graceful degradation:</b> Uses {@link ObjectProvider} for
 * {@link TrinoConfig} so the bean can be constructed even when no config
 * is registered (dev/CI). Connection failures are caught and logged;
 * {@link #execute} returns an empty result rather than throwing.
 *
 * <p>Thread-safety (D-039): each {@link #execute} call opens and closes its
 * own JDBC connection — no shared mutable state.
 */
@Component
public class TrinoExecutor {

    private static final Logger log = LoggerFactory.getLogger(TrinoExecutor.class);

    private final ObjectProvider<TrinoConfig> configProvider;

    /**
     * Construct with a config provider.
     *
     * @param configProvider Spring {@link ObjectProvider} for
     *        {@link TrinoConfig}; may be null in tests
     */
    @org.springframework.beans.factory.annotation.Autowired
    public TrinoExecutor(ObjectProvider<TrinoConfig> configProvider) {
        this.configProvider = configProvider;
    }

    /** Default constructor — no config (dev/CI mode). */
    public TrinoExecutor() {
        this(null);
    }

    /**
     * Whether the Trino execution path is enabled.
     *
     * @return {@code true} when EP_DEV_ONLY is false and a config is available
     */
    public boolean isEnabled() {
        if (isDevOnly()) {
            return false;
        }
        return resolveConfig() != null;
    }

    /**
     * Execute a validated SELECT query against Trino and return results.
     *
     * <p>Returns an empty {@link TrinoQueryResult} when:
     * <ul>
     *   <li>EP_DEV_ONLY is active (dev mode)</li>
     *   <li>No {@link TrinoConfig} is available</li>
     *   <li>The SQL is null/blank</li>
     *   <li>The query fails (connection error, SQL error, timeout)</li>
     * </ul>
     *
     * @param sql validated SELECT query
     * @param maxRows maximum rows to return (0 = unlimited)
     * @return query result with columns and rows
     */
    public TrinoQueryResult execute(String sql, int maxRows) {
        if (sql == null || sql.isBlank()) {
            return TrinoQueryResult.empty("SQL is empty");
        }
        if (isDevOnly()) {
            log.debug("[TrinoExecutor] EP_DEV_ONLY active — execution skipped");
            return TrinoQueryResult.empty("EP_DEV_ONLY active");
        }
        TrinoConfig config = resolveConfig();
        if (config == null) {
            log.debug("[TrinoExecutor] No TrinoConfig available — execution skipped");
            return TrinoQueryResult.empty("No TrinoConfig available");
        }

        long start = System.currentTimeMillis();
        try (Connection conn = openConnection(config)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(config.queryTimeoutSec());
                if (maxRows > 0) {
                    stmt.setMaxRows(maxRows);
                }
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    return extractResult(rs, maxRows);
                }
            }
        } catch (SQLException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[TrinoExecutor] Query failed after {}ms: {}", elapsed, e.getMessage());
            return TrinoQueryResult.empty("Query failed: " + e.getMessage());
        }
    }

    /**
     * Execute a prepared statement with parameter substitution.
     *
     * <p>This variant is safer for user-supplied values but the NL Query
     * pipeline primarily uses the string-based {@link #execute} since SQL is
     * LLM-generated and validated. This method is provided for programmatic
     * callers.
     *
     * @param sql    parameterized SQL (using {@code ?} placeholders)
     * @param params parameter values in order
     * @param maxRows maximum rows to return (0 = unlimited)
     * @return query result
     */
    public TrinoQueryResult executePrepared(String sql, List<Object> params, int maxRows) {
        if (sql == null || sql.isBlank()) {
            return TrinoQueryResult.empty("SQL is empty");
        }
        if (isDevOnly()) {
            return TrinoQueryResult.empty("EP_DEV_ONLY active");
        }
        TrinoConfig config = resolveConfig();
        if (config == null) {
            return TrinoQueryResult.empty("No TrinoConfig available");
        }

        try (Connection conn = openConnection(config)) {
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setQueryTimeout(config.queryTimeoutSec());
                if (maxRows > 0) {
                    stmt.setMaxRows(maxRows);
                }
                if (params != null) {
                    for (int i = 0; i < params.size(); i++) {
                        stmt.setObject(i + 1, params.get(i));
                    }
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    return extractResult(rs, maxRows);
                }
            }
        } catch (SQLException e) {
            log.warn("[TrinoExecutor] Prepared query failed: {}", e.getMessage());
            return TrinoQueryResult.empty("Query failed: " + e.getMessage());
        }
    }

    // ─── Internal helpers ─────────────────────────────────────────────

    private Connection openConnection(TrinoConfig config) throws SQLException {
        String url = config.fullUrl();
        Properties props = new Properties();
        props.setProperty("user", config.user());
        if (config.password() != null && !config.password().isBlank()) {
            props.setProperty("password", config.password());
        }
        return DriverManager.getConnection(url, props);
    }

    private TrinoQueryResult extractResult(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int rowCount = 0;
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                Object value = rs.getObject(i);
                row.put(columns.get(i - 1), value);
            }
            rows.add(row);
            rowCount++;
            if (maxRows > 0 && rowCount >= maxRows) {
                break;
            }
        }
        return new TrinoQueryResult(true, columns, rows, rowCount, null);
    }

    private TrinoConfig resolveConfig() {
        if (configProvider == null) {
            return null;
        }
        return configProvider.getIfAvailable();
    }

    private static boolean isDevOnly() {
        String dev = System.getenv("EP_DEV_ONLY");
        if (dev == null || dev.isBlank()) {
            return true;
        }
        return !"false".equalsIgnoreCase(dev);
    }
}
