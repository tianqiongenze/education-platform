package com.ep.data.lineage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * In-memory directed lineage graph (§12.1.2).
 *
 * <p>Holds {@link LineageEdge}s in two {@link ConcurrentHashMap} indexes —
 * keyed by target table (for downstream traversal) and by source table (for
 * upstream traversal). The structure is thread-safe via ConcurrentHashMap
 * per D-039 (no synchronized blocks).
 *
 * <p>This is the dev/CI in-memory fallback (same pattern as
 * MetadataController). Production deployments persist edges to the Iceberg
 * {@code meta.column_lineage} table and load them into the graph at startup.
 */
@Component
public class LineageGraph {

    /** Edges where the key is the target table (downstream index). */
    private final Map<String, Set<LineageEdge>> byTarget = new ConcurrentHashMap<>();

    /** Edges where the key is the source table (upstream index). */
    private final Map<String, Set<LineageEdge>> bySource = new ConcurrentHashMap<>();

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
