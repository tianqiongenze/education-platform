package com.ep.data.nlquery;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes NL Query audit records to the {@code audit.nl_query_log} table
 * (D-190).
 *
 * <p><b>EP_DEV_ONLY guard:</b> When {@code EP_DEV_ONLY} is unset, blank,
 * or {@code "true"}, records are stored in an in-memory
 * {@link CopyOnWriteArrayList} (D-039 thread-safety). Production
 * deployments set {@code EP_DEV_ONLY=false} and provide a
 * {@link JdbcTemplate} backed by TiDB — records are batch-inserted into
 * {@code audit.nl_query_log}.
 *
 * <p>Uses {@link ObjectProvider} for {@link JdbcTemplate} so the bean
 * can be constructed even when no datasource is configured (dev/CI).
 *
 * <p>Schema ({@code audit.nl_query_log}):
 * <pre>
 * CREATE TABLE audit.nl_query_log (
 *   id           BIGINT NOT NULL AUTO_RANDOM,
 *   user_id      VARCHAR(128) NOT NULL,
 *   session_id   VARCHAR(128),
 *   question     TEXT NOT NULL,
 *   intent       VARCHAR(32),
 *   generated_sql TEXT,
 *   is_valid     BOOLEAN NOT NULL,
 *   violations   TEXT,
 *   executed     BOOLEAN NOT NULL,
 *   row_count    INT NOT NULL DEFAULT 0,
 *   latency_ms   BIGINT NOT NULL,
 *   created_at   DATETIME NOT NULL
 * );
 * </pre>
 */
@Component
public class IcebergAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(IcebergAuditWriter.class);

    private static final String INSERT_SQL =
            "INSERT INTO audit.nl_query_log "
                    + "(user_id, session_id, question, intent, generated_sql, is_valid, "
                    + "violations, executed, row_count, latency_ms, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    /** Dev/CI in-memory audit log (D-039: CopyOnWriteArrayList). */
    private final CopyOnWriteArrayList<NlQueryAuditRecord> devLog = new CopyOnWriteArrayList<>();

    /**
     * Construct with a JdbcTemplate provider.
     *
     * @param jdbcTemplateProvider Spring {@link ObjectProvider} for
     *        {@link JdbcTemplate}; may be null in tests
     */
    @org.springframework.beans.factory.annotation.Autowired
    public IcebergAuditWriter(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    /** Default constructor — dev/CI mode (no JDBC). */
    public IcebergAuditWriter() {
        this(null);
    }

    /**
     * Write a single audit record.
     *
     * <p>In dev/CI mode, records are stored in-memory. In production,
     * records are written to {@code audit.nl_query_log} via JdbcTemplate.
     * Write failures are logged and do not throw (audit is best-effort).
     *
     * @param record the audit record to write
     */
    public void write(NlQueryAuditRecord record) {
        if (record == null) {
            return;
        }

        if (isDevOnly()) {
            devLog.add(record);
            log.debug("[IcebergAuditWriter] EP_DEV_ONLY active — audit logged in-memory: user={} latency={}ms",
                    record.userId(), record.latencyMs());
            return;
        }

        JdbcTemplate jdbc = resolveJdbcTemplate();
        if (jdbc == null) {
            // No JDBC available — fall back to in-memory.
            devLog.add(record);
            log.debug("[IcebergAuditWriter] No JdbcTemplate — audit logged in-memory");
            return;
        }

        try {
            jdbc.update(INSERT_SQL,
                    record.userId(),
                    record.sessionId(),
                    truncate(record.question(), 65535),
                    record.intent(),
                    record.generatedSql(),
                    record.isValid(),
                    record.violations(),
                    record.executed(),
                    record.rowCount(),
                    record.latencyMs(),
                    record.timestamp() != null ? record.timestamp() : LocalDateTime.now());
            log.debug("[IcebergAuditWriter] Audit record written to audit.nl_query_log: user={} latency={}ms",
                    record.userId(), record.latencyMs());
        } catch (Exception e) {
            // Audit write failure should not break the query pipeline.
            devLog.add(record);
            log.warn("[IcebergAuditWriter] JDBC audit write failed, falling back to in-memory: {}", e.getMessage());
        }
    }

    /**
     * Return a snapshot of the dev/CI in-memory audit log.
     *
     * <p>In production, this returns only records that were written to
     * the in-memory fallback (e.g. when the JDBC write failed). Use a SQL
     * query against {@code audit.nl_query_log} for production audit reads.
     *
     * @return snapshot of in-memory audit records
     */
    public List<NlQueryAuditRecord> getDevLog() {
        return new ArrayList<>(devLog);
    }

    /**
     * Clear the in-memory dev log (for testing).
     */
    public void clearDevLog() {
        devLog.clear();
    }

    // ─── Internal helpers ─────────────────────────────────────────────

    private JdbcTemplate resolveJdbcTemplate() {
        if (jdbcTemplateProvider == null) {
            return null;
        }
        return jdbcTemplateProvider.getIfAvailable();
    }

    private static boolean isDevOnly() {
        String dev = System.getenv("EP_DEV_ONLY");
        if (dev == null || dev.isBlank()) {
            return true;
        }
        return !"false".equalsIgnoreCase(dev);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
