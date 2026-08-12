package com.ep.data.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for querying the Data Fabric metadata catalog (D-074).
 *
 * <p>Minimal viable subset of sp02-sdd §12.1 / ADR-D05: list tables, list
 * columns for a table, and free-text search by name. GNN lineage and NL
 * query (M4) are out of scope.
 *
 * <p>In the minimal layer, collected metadata is held in an in-process
 * {@link ConcurrentHashMap} so the controller is fully functional in
 * dev/CI without a live Iceberg cluster. Production deployments wire
 * {@link MetadataCollector#store} to a real Iceberg sink and the controller
 * queries the same table; the in-memory map is a dev fallback only.
 */
@RestController
@RequestMapping("/api/v1/metadata")
@Tag(name = "元数据管理", description = "表/字段元数据查询与搜索")
public class MetadataController {

    private static final Logger log = LoggerFactory.getLogger(MetadataController.class);

    private final MetadataCollector collector;

    /** Dev/CI fallback store; keyed by "database.tableName". */
    private final Map<String, List<MetadataCollector.ColumnMetadata>> store = new ConcurrentHashMap<>();

    public MetadataController(MetadataCollector collector) {
        this.collector = collector;
    }

    /**
     * List all collected table names.
     *
     * @return list of {@code "database.table"} entries
     */
    @GetMapping("/tables")
    public ResponseEntity<List<String>> listTables() {
        return ResponseEntity.ok(new ArrayList<>(store.keySet()));
    }

    /**
     * List columns for a specific table.
     *
     * @param table the table name (may be qualified as {@code database.table})
     * @return column metadata for the table, or 404 if unknown
     */
    @GetMapping("/tables/{table}/columns")
    public ResponseEntity<List<MetadataCollector.ColumnMetadata>> listColumns(
            @PathVariable String table) {
        List<MetadataCollector.ColumnMetadata> cols = store.get(resolveKey(table));
        if (cols == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(cols);
    }

    /**
     * Search tables and columns by name (case-insensitive contains).
     *
     * @param q search keyword
     * @return matching column metadata (a table match returns all its columns)
     */
    @GetMapping("/search")
    public ResponseEntity<List<MetadataCollector.ColumnMetadata>> search(
            @RequestParam(name = "q", defaultValue = "") String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<MetadataCollector.ColumnMetadata> hits = store.values().stream()
                .flatMap(List::stream)
                .filter(c -> containsIgnoreCase(c.tableName(), needle)
                        || containsIgnoreCase(c.columnName(), needle)
                        || containsIgnoreCase(c.database(), needle))
                .collect(Collectors.toList());
        return ResponseEntity.ok(hits);
    }

    /**
     * Ingest a CREATE TABLE DDL event (dev convenience endpoint; production
     * path consumes Pulsar {@code persistent://public/cdc/ddl}).
     *
     * @param cmd the DDL event payload
     * @return the parsed column metadata
     */
    @PostMapping("/ddl")
    public ResponseEntity<List<MetadataCollector.ColumnMetadata>> ingestDdl(
            @RequestBody DdlEventCmd cmd) {
        if (cmd == null || cmd.ddlSql() == null || cmd.ddlSql().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        List<MetadataCollector.ColumnMetadata> parsed = collector.collectFromDdlEvent(
                cmd.tenantId() == null ? "default" : cmd.tenantId(),
                cmd.database() == null ? "default" : cmd.database(),
                cmd.tableName(),
                cmd.ddlSql());
        if (!parsed.isEmpty()) {
            String key = (cmd.database() == null ? "default" : cmd.database()) + "." + cmd.tableName();
            store.put(key, parsed);
            collector.store(parsed);
            log.info("[MetadataController] Ingested DDL for {} — {} columns", key, parsed.size());
        }
        return ResponseEntity.ok(parsed);
    }

    /** Resolve a path variable into the store key, handling {@code database.table} form. */
    private static String resolveKey(String table) {
        if (table == null) {
            return "";
        }
        return table.contains(".") ? table : "default." + table;
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * Payload for {@code POST /api/v1/metadata/ddl}.
     */
    public record DdlEventCmd(
            String tenantId,
            String database,
            String tableName,
            String ddlSql
    ) {
    }
}
