package com.ep.data.lineage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * In-memory directed lineage graph (§12.1.2) with TiDB persistence
 * (D-188 upgrade).
 *
 * <p>Holds {@link LineageEdge}s in two {@link ConcurrentHashMap} indexes —
 * keyed by target table (for downstream traversal) and by source table (for
 * upstream traversal). The structure is thread-safe via ConcurrentHashMap
 * per D-039 (no synchronized blocks).
 *
 * <p><b>Persistence (D-188):</b> When a {@link JdbcTemplate} is available
 * (production mode, TiDB-backed), edges are persisted to the
 * {@code meta.column_lineage} table via {@link #persistEdges(List)} and loaded
 * at startup via {@link #loadPersistedEdges()}. In dev/CI (no JdbcTemplate),
 * the in-memory graph is the sole store — the same fallback pattern used by
 * {@code MetadataController}.
 */
@Component
public class LineageGraph {

    private static final Logger log = LoggerFactory.getLogger(LineageGraph.class);

    /** Edges where the key is the target table (downstream index). */
    private final Map<String, Set<LineageEdge>> byTarget = new ConcurrentHashMap<>();

    /** Edges where the key is the source table (upstream index). */
    private final Map<String, Set<LineageEdge>> bySource = new ConcurrentHashMap<>();

    /**
     * Optional JdbcTemplate for TiDB persistence. Null in dev/CI (no DB
     * configured) — see {@link ObjectProvider}.
     */
    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    /** Default constructor for dev/CI (no persistence). */
    @org.springframework.beans.factory.annotation.Autowired
    public LineageGraph() {
        this.jdbcTemplateProvider = null;
    }

    /**
     * Construct with an optional {@link JdbcTemplate} provider for TiDB
     * persistence.
     *
     * @param jdbcTemplateProvider Spring {@link ObjectProvider} that yields a
     *        {@link JdbcTemplate} when a DataSource is configured, or null
     *        otherwise
     */
    public LineageGraph(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    /**
     * Add a batch of lineage edges to the graph.
     *
     * @param edges edges to add (null/empty tolerated)
     */
    public void addEdges(List<LineageEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return;
        }
        for (LineageEdge e : edges) {
            byTarget.computeIfAbsent(e.targetTable(), k -> ConcurrentHashMap.newKeySet()).add(e);
            bySource.computeIfAbsent(e.sourceTable(), k -> ConcurrentHashMap.newKeySet()).add(e);
        }
    }

    /**
     * Persist a batch of lineage edges to the {@code meta.column_lineage}
     * TiDB table (D-188).
     *
     * <p>In dev/CI (no JdbcTemplate available), this is a no-op that logs a
     * warning — edges remain in the in-memory graph only.
     *
     * @param edges edges to persist (null/empty tolerated)
     */
    public void persistEdges(List<LineageEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return;
        }
        JdbcTemplate jdbc = jdbcTemplateProvider != null ? jdbcTemplateProvider.getIfAvailable() : null;
        if (jdbc == null) {
            log.warn("[LineageGraph] EP_DEV_ONLY active — persistEdges is a no-op (no JdbcTemplate). "
                    + "{} edges remain in-memory only.", edges.size());
            return;
        }
        String sql = "INSERT INTO meta.column_lineage "
                + "(source_table, source_column, target_table, target_column, confidence, inferred_by) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        List<Object[]> batch = new ArrayList<>(edges.size());
        for (LineageEdge e : edges) {
            batch.add(new Object[]{
                    e.sourceTable(), e.sourceColumn(),
                    e.targetTable(), e.targetColumn(),
                    e.confidence(), e.inferredBy()
            });
        }
        try {
            jdbc.batchUpdate(sql, batch);
            log.info("[LineageGraph] Persisted {} edges to meta.column_lineage", edges.size());
        } catch (Exception ex) {
            log.error("[LineageGraph] Failed to persist edges to TiDB: {}", ex.getMessage());
        }
    }

    /**
     * Load all persisted edges from the {@code meta.column_lineage} TiDB table
     * into the in-memory graph (D-188).
     *
     * <p>In dev/CI (no JdbcTemplate available), this is a no-op.
     *
     * @return number of edges loaded (0 in dev mode or empty table)
     */
    public int loadPersistedEdges() {
        JdbcTemplate jdbc = jdbcTemplateProvider != null ? jdbcTemplateProvider.getIfAvailable() : null;
        if (jdbc == null) {
            log.info("[LineageGraph] EP_DEV_ONLY active — loadPersistedEdges is a no-op (no JdbcTemplate).");
            return 0;
        }
        String sql = "SELECT source_table, source_column, target_table, target_column, confidence, inferred_by "
                + "FROM meta.column_lineage";
        List<LineageEdge> loaded = new ArrayList<>();
        try {
            loaded = jdbc.query(sql, (rs, rowNum) -> new LineageEdge(
                    rs.getString("source_table"),
                    rs.getString("source_column"),
                    rs.getString("target_table"),
                    rs.getString("target_column"),
                    rs.getDouble("confidence"),
                    rs.getString("inferred_by")
            ));
        } catch (Exception ex) {
            log.error("[LineageGraph] Failed to load persisted edges from TiDB: {}", ex.getMessage());
            return 0;
        }
        addEdges(loaded);
        log.info("[LineageGraph] Loaded {} persisted edges from meta.column_lineage", loaded.size());
        return loaded.size();
    }

    /**
     * BFS downstream traversal — tables that {@code table} feeds into.
     *
     * @param table    starting table
     * @param maxDepth maximum BFS depth (1 = direct children)
     * @return set of downstream table names (excludes the start; never null)
     */
    public Set<String> downstream(String table, int maxDepth) {
        return bfs(table, maxDepth, byTarget, true);
    }

    /**
     * BFS upstream traversal — tables that feed into {@code table}.
     *
     * @param table    starting table
     * @param maxDepth maximum BFS depth (1 = direct parents)
     * @return set of upstream table names (excludes the start; never null)
     */
    public Set<String> upstream(String table, int maxDepth) {
        return bfs(table, maxDepth, bySource, false);
    }

    /**
     * Direct edges involving the given table (as either source or target).
     *
     * @param table the table to look up
     * @return direct edges (never null)
     */
    public List<LineageEdge> getEdgesForTable(String table) {
        List<LineageEdge> out = new ArrayList<>();
        Set<LineageEdge> asTarget = byTarget.getOrDefault(table, Collections.emptySet());
        Set<LineageEdge> asSource = bySource.getOrDefault(table, Collections.emptySet());
        out.addAll(asTarget);
        out.addAll(asSource);
        return out;
    }

    private Set<String> bfs(String start, int maxDepth, Map<String, Set<LineageEdge>> index, boolean followTarget) {
        Set<String> visited = new LinkedHashSet<>();
        if (start == null || maxDepth <= 0) {
            return visited;
        }
        Set<String> frontier = new HashSet<>();
        frontier.add(start);
        for (int depth = 0; depth < maxDepth; depth++) {
            Set<String> next = new LinkedHashSet<>();
            for (String node : frontier) {
                Set<LineageEdge> edges = index.get(node);
                if (edges == null) {
                    continue;
                }
                for (LineageEdge e : edges) {
                    String neighbor = followTarget ? e.targetTable() : e.sourceTable();
                    if (!neighbor.equals(start) && !visited.contains(neighbor) && !frontier.contains(neighbor)) {
                        next.add(neighbor);
                    }
                }
            }
            visited.addAll(next);
            if (next.isEmpty()) {
                break;
            }
            frontier = next;
        }
        return visited;
    }
}
