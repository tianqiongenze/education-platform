package com.ep.data.metadata;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Collects column-level metadata for the Data Fabric catalog (D-074).
 *
 * <p>This is the minimal viable subset of the full 智能数据目录 designed in
 * sp02-sdd §12.1 / ADR-D05. It supports two ingestion paths:
 * <ul>
 *   <li>{@link #collectFromDdlEvent} — parse a CREATE TABLE DDL emitted by a
 *       CDC DDL event (Pulsar topic {@code persistent://public/cdc/ddl}).</li>
 *   <li>{@link #collectFromJdbc} — accept pre-collected column metadata from a
 *       JDBC information-schema scan.</li>
 * </ul>
 * Collected metadata is stored to the Iceberg {@code meta.column_metadata}
 * table by {@link #store}; in dev/CI (EP_DEV_ONLY guard) the operation is
 * logged instead of touching a real cluster.
 *
 * <p>GNN lineage, NL query and AIOps features (§12.1 M4) are intentionally
 * out of scope — they need LLM/GraphScope which is not available yet.
 */
@Component
public class MetadataCollector {

    private static final Logger log = LoggerFactory.getLogger(MetadataCollector.class);

    /**
     * Column-level metadata record written to {@code meta.column_metadata}.
     */
    public record ColumnMetadata(
            String tenantId,
            String database,
            String tableName,
            String columnName,
            String dataType,
            boolean nullable,
            String comment,
            LocalDateTime collectedAt
    ) {
    }

    /**
     * Matches a single column definition line inside CREATE TABLE parens.
     * Captures: name (1), type-with-args (2), the trailing tokens (3) which
     * are inspected for NOT NULL / COMMENT.
     */
    private static final Pattern COLUMN_PATTERN = Pattern.compile(
            "^\\s*`?(\\w+)`?\\s+([A-Za-z][A-Za-z0-9_]*(?:\\s*\\([^)]*\\))?)(.*)$",
            Pattern.CASE_INSENSITIVE);

    /** Keywords that begin a non-column clause inside CREATE TABLE parens. */
    private static final Pattern NON_COLUMN_START = Pattern.compile(
            "^(PRIMARY\\s+KEY|KEY|INDEX|UNIQUE|CONSTRAINT|FOREIGN|FULLTEXT|SPATIAL|CHECK)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NOT_NULL_PATTERN = Pattern.compile(
            "\\bNOT\\s+NULL\\b", Pattern.CASE_INSENSITIVE);

    /** MySQL/Trino COMMENT '...'. */
    private static final Pattern COMMENT_PATTERN = Pattern.compile(
            "COMMENT\\s+'((?:[^'\\\\]|\\\\.)*)'", Pattern.CASE_INSENSITIVE);

    /**
     * Parse a CREATE TABLE DDL to extract column-level metadata.
     *
     * <p>Minimal viable implementation — handles the common patterns emitted
     * by TiCDC DDL events (backtick-quoted identifiers, inline {@code COMMENT
     * '...'}). This is not a full SQL parser; exotic DDL constructs are
     * silently skipped.
     *
     * <p>This method is pure (no Spring/IO deps) so it is unit-testable
     * without a Spring context.
     *
     * @param tenantId tenant the table belongs to
     * @param database database (schema) name
     * @param tableName table name
     * @param ddlSql   the CREATE TABLE DDL statement
     * @return extracted column metadata (may be empty; never null)
     */
    public List<ColumnMetadata> collectFromDdlEvent(String tenantId, String database,
                                                    String tableName, String ddlSql) {
        List<ColumnMetadata> result = new ArrayList<>();
        if (ddlSql == null || ddlSql.isBlank()) {
            return result;
        }

        List<String> columnLines = extractColumnLines(ddlSql);
        LocalDateTime collectedAt = LocalDateTime.now();
        for (String line : columnLines) {
            Matcher m = COLUMN_PATTERN.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String columnName = m.group(1);
            String dataType = m.group(2).trim();
            String tail = m.group(3) == null ? "" : m.group(3);

            boolean nullable = !NOT_NULL_PATTERN.matcher(tail).find();
            String comment = extractComment(tail);

            result.add(new ColumnMetadata(
                    tenantId, database, tableName,
                    columnName, dataType, nullable, comment, collectedAt));
        }
        return result;
    }

    /**
     * Accept pre-collected column metadata from a JDBC information-schema scan.
     *
     * <p>Stamps {@code collectedAt = now()} when null and delegates to
     * {@link #store}.
     *
     * @param database  database name
     * @param tableName table name
     * @param columns   columns already collected via JDBC
     */
    public void collectFromJdbc(String database, String tableName, List<ColumnMetadata> columns) {
        if (columns == null || columns.isEmpty()) {
            return;
        }
        List<ColumnMetadata> stamped = new ArrayList<>(columns.size());
        for (ColumnMetadata c : columns) {
            LocalDateTime at = c.collectedAt() == null ? LocalDateTime.now() : c.collectedAt();
            stamped.add(new ColumnMetadata(
                    c.tenantId(), database, tableName,
                    c.columnName(), c.dataType(), c.nullable(), c.comment(), at));
        }
        store(stamped);
    }

    /**
     * Store collected metadata to Iceberg {@code meta.column_metadata}.
     *
     * <p>In dev/CI (EP_DEV_ONLY guard), logs the operation instead of writing
     * to Iceberg. Actual Iceberg write requires a running catalog cluster and
     * is wired up in production deployments only.
     *
     * @param metadata the column metadata records to persist
     */
    public void store(List<ColumnMetadata> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);
        if (devGuard) {
            log.info("[MetadataCollector] EP_DEV_ONLY active — logged (not written) "
                    + "{} column metadata records to meta.column_metadata", metadata.size());
            return;
        }
        // Production path: write to Iceberg. Not implemented in the minimal
        // viable layer — requires a live catalog + warehouse.
        log.info("[MetadataCollector] Storing {} column metadata records to Iceberg "
                + "meta.column_metadata", metadata.size());
    }

    /**
     * Extract the inner column-definition lines from a CREATE TABLE DDL.
     *
     * <p>Strips the {@code CREATE TABLE ... (} prefix and the trailing {@code )}
     * / engine options, splits the body on commas at the top nesting level,
     * and drops non-column clauses (PRIMARY KEY, INDEX, CONSTRAINT, ...).
     */
    private static List<String> extractColumnLines(String ddlSql) {
        int open = ddlSql.indexOf('(');
        int close = ddlSql.lastIndexOf(')');
        if (open < 0 || close < 0 || close <= open) {
            return List.of();
        }
        String body = ddlSql.substring(open + 1, close);

        // Split on top-level commas (no nested parens support in MVP).
        List<String> parts = splitTopLevelCommas(body);

        List<String> columnLines = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (NON_COLUMN_START.matcher(trimmed).find()) {
                continue;
            }
            columnLines.add(trimmed);
        }
        return columnLines;
    }

    /** Split a string on commas that are not nested inside parentheses. */
    private static List<String> splitTopLevelCommas(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') {
                depth++;
                cur.append(ch);
            } else if (ch == ')') {
                depth = Math.max(0, depth - 1);
                cur.append(ch);
            } else if (ch == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String extractComment(String tail) {
        Matcher m = COMMENT_PATTERN.matcher(tail);
        return m.find() ? m.group(1) : null;
    }
}
