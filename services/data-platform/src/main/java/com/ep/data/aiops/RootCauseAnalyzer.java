package com.ep.data.aiops;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Root-cause analyzer that walks a hardcoded topology DAG and matches
 * anomalous metrics against association rules (§12.3.3).
 *
 * <p>Topology DAG (data-flow direction):
 * <pre>
 *   CDC → Pulsar → Flink → Iceberg → Trino
 * </pre>
 *
 * <p>Each topology node owns one or more metrics:
 * <ul>
 *   <li>CDC → {@code CDC_DELAY}</li>
 *   <li>Pulsar → {@code PULSAR_BACKLOG}</li>
 *   <li>Flink → {@code FLINK_CHECKPOINT_FAIL}</li>
 *   <li>Iceberg → {@code ICEBERG_COMPACTION_FREQ}</li>
 *   <li>Trino → {@code TRINO_P99}</li>
 * </ul>
 *
 * <p>Association rules map a pair of anomalous metrics (sorted, joined with
 * {@code +}) to a candidate root cause. When a rule matches the confidence
 * is {@code 1.0}. When only a topology path is found the confidence is
 * {@code 0.5}. Unknown metrics get {@code 0.1}.
 *
 * <p>Marked {@code @Component} so it can be constructor-injected by the
 * controller, while remaining a plain POJO instantiable with {@code new}
 * in tests.
 */
@Component
public final class RootCauseAnalyzer {

    /** Ordered topology nodes (data-flow direction). */
    private static final List<String> TOPOLOGY_NODES =
            List.of("CDC", "PULSAR", "FLINK", "ICEBERG", "TRINO");

    /** Adjacency list encoding {@code source → downstream targets}. */
    private static final Map<String, List<String>> DOWNSTREAM_EDGES = new HashMap<>();
    static {
        DOWNSTREAM_EDGES.put("CDC", List.of("PULSAR"));
        DOWNSTREAM_EDGES.put("PULSAR", List.of("FLINK"));
        DOWNSTREAM_EDGES.put("FLINK", List.of("ICEBERG"));
        DOWNSTREAM_EDGES.put("ICEBERG", List.of("TRINO"));
        DOWNSTREAM_EDGES.put("TRINO", List.of());
    }

    /** Metric name → owning topology node. */
    private static final Map<String, String> METRIC_TO_NODE = new HashMap<>();
    static {
        METRIC_TO_NODE.put("CDC_DELAY", "CDC");
        METRIC_TO_NODE.put("PULSAR_BACKLOG", "PULSAR");
        METRIC_TO_NODE.put("FLINK_CHECKPOINT_FAIL", "FLINK");
        METRIC_TO_NODE.put("ICEBERG_COMPACTION_FREQ", "ICEBERG");
        METRIC_TO_NODE.put("TRINO_P99", "TRINO");
    }

    /** Association rules: sorted metric pair → root-cause description. */
    private static final Map<String, String> ASSOCIATION_RULES = new HashMap<>();
    static {
        ASSOCIATION_RULES.put("CDC_DELAY+FLINK_CHECKPOINT_FAIL", "TiDB GC stall — check tikv_gc_life_time");
        ASSOCIATION_RULES.put("PULSAR_BACKLOG+FLINK_CHECKPOINT_FAIL", "Pulsar broker overload — check broker CPU/memory");
        ASSOCIATION_RULES.put("ICEBERG_COMPACTION_FREQ+TRINO_P99", "Iceberg compaction storm — throttle compaction");
        ASSOCIATION_RULES.put("CDC_DELAY+PULSAR_BACKLOG", "CDC pipeline bottleneck — check TiDB CDC connector health");
    }

    /** Suggested actions keyed by the same rule key as the association map. */
    private static final Map<String, String> RULE_ACTIONS = new HashMap<>();
    static {
        RULE_ACTIONS.put("CDC_DELAY+FLINK_CHECKPOINT_FAIL", "Increase tikv_gc_life_time and verify TiKV regions are healthy");
        RULE_ACTIONS.put("PULSAR_BACKLOG+FLINK_CHECKPOINT_FAIL", "Scale Pulsar brokers or check for slow consumers");
        RULE_ACTIONS.put("ICEBERG_COMPACTION_FREQ+TRINO_P99", "Throttle Iceberg compaction and review rewrite_data_files concurrency");
        RULE_ACTIONS.put("CDC_DELAY+PULSAR_BACKLOG", "Check TiDB CDC connector health and TiKV CDC sink throughput");
    }

    /**
     * Analyze a set of anomalous metrics and produce a root-cause report.
     *
     * @param anomalousMetrics set of anomalous metric names (may be null/empty)
     * @return a {@link RootCauseReport}; empty for null/empty input
     */
    public RootCauseReport analyze(Set<String> anomalousMetrics) {
        if (anomalousMetrics == null || anomalousMetrics.isEmpty()) {
            return new RootCauseReport(List.of(), 0.0, List.of(), List.of());
        }

        // Normalize + de-duplicate while preserving insertion order.
        LinkedHashSet<String> metrics = new LinkedHashSet<>();
        for (String m : anomalousMetrics) {
            if (m != null && !m.isBlank()) {
                metrics.add(m.toUpperCase());
            }
        }
        if (metrics.isEmpty()) {
            return new RootCauseReport(List.of(), 0.0, List.of(), List.of());
        }

        // 1. Try association-rule match across all metric pairs.
        // Rules are keyed by the alphabetically-sorted pair (e.g.
        // "A+B"), so both directions resolve to the same key.
        List<String> sortedMetrics = new ArrayList<>(metrics);
        Collections.sort(sortedMetrics);
        for (int i = 0; i < sortedMetrics.size(); i++) {
            for (int j = i + 1; j < sortedMetrics.size(); j++) {
                String key = sortedMetrics.get(i) + "+" + sortedMetrics.get(j);
                String cause = ASSOCIATION_RULES.get(key);
                if (cause == null) {
                    // Fall back to the reverse-order key for rules stored
                    // in non-alphabetical order.
                    cause = ASSOCIATION_RULES.get(sortedMetrics.get(j) + "+" + sortedMetrics.get(i));
                    key = sortedMetrics.get(j) + "+" + sortedMetrics.get(i);
                }
                if (cause != null) {
                    List<String> candidates = List.of(cause);
                    List<String> actions = List.of(
                            RULE_ACTIONS.getOrDefault(key, "Investigate " + cause));
                    List<String> path = topologyPath(metrics);
                    return new RootCauseReport(candidates, 1.0, actions, path);
                }
            }
        }

        // 2. Topology-only path (BFS from each anomalous node upstream to root).
        List<String> path = topologyPath(metrics);
        if (!path.isEmpty()) {
            String root = path.get(0);
            List<String> candidates = List.of(root + " node is the likely root cause (topology inference)");
            List<String> actions = List.of("Investigate " + root + " subsystem and downstream propagation");
            return new RootCauseReport(candidates, 0.5, actions, path);
        }

        // 3. Unknown metrics.
        List<String> candidates = List.of("Unknown root cause — metrics not recognized in topology");
        List<String> actions = List.of("Manual investigation required for: " + String.join(", ", sortedMetrics));
        return new RootCauseReport(candidates, 0.1, actions, List.of());
    }

    /**
     * Build a topology path from the anomalous metrics back to the
     * upstream-most affected node using BFS over the reverse DAG.
     *
     * @param metrics normalized anomalous metric names
     * @return ordered node names from root → anomaly site (empty if no node recognized)
     */
    private static List<String> topologyPath(Set<String> metrics) {
        // Map metrics to their owning nodes.
        LinkedHashSet<String> anomalousNodes = new LinkedHashSet<>();
        for (String m : metrics) {
            String node = METRIC_TO_NODE.get(m);
            if (node != null) {
                anomalousNodes.add(node);
            }
        }
        if (anomalousNodes.isEmpty()) {
            return List.of();
        }

        // BFS upstream from each anomalous node; collect the union of paths.
        // The "root" is the upstream-most node reachable from any anomaly.
        LinkedHashSet<String> fullPath = new LinkedHashSet<>();
        for (String start : anomalousNodes) {
            List<String> path = bfsUpstream(start);
            // path is root → start; prepend into the union set.
            for (String node : path) {
                fullPath.add(node);
            }
        }

        // Order the union by the canonical topology order (root-first).
        List<String> ordered = new ArrayList<>();
        for (String node : TOPOLOGY_NODES) {
            if (fullPath.contains(node)) {
                ordered.add(node);
            }
        }
        return ordered;
    }

    /**
     * BFS upstream from {@code start} to the head of the DAG.
     *
     * @param start starting topology node
     * @return ordered path from the upstream-most root to {@code start}
     */
    private static List<String> bfsUpstream(String start) {
        Deque<String> queue = new ArrayDeque<>();
        Map<String, String> parent = new HashMap<>();
        queue.add(start);
        parent.put(start, null);
        String furthest = start;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            furthest = current;
            for (Map.Entry<String, List<String>> entry : DOWNSTREAM_EDGES.entrySet()) {
                if (entry.getValue().contains(current) && !parent.containsKey(entry.getKey())) {
                    parent.put(entry.getKey(), current);
                    queue.add(entry.getKey());
                }
            }
        }
        // Walk back from furthest to start to build root → start path.
        List<String> path = new ArrayList<>();
        String cursor = furthest;
        while (cursor != null) {
            path.add(cursor);
            cursor = parent.get(cursor);
        }
        Collections.reverse(path);
        return path;
    }
}
