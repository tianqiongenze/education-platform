package com.ep.data.aiops;

import java.util.List;

/**
 * Root-cause analysis report produced from a set of anomalous metrics
 * (§12.3.3).
 *
 * <p>The analyzer walks the topology DAG (CDC → Pulsar → Flink → Iceberg →
 * Trino) using BFS and matches pairs of anomalous metrics against
 * association rules to produce a confidence-scored report.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code rootCauseCandidates} — ranked candidate root causes.</li>
 *   <li>{@code confidence} — 1.0 (rule match), 0.5 (topology only),
 *       0.1 (unknown).</li>
 *   <li>{@code suggestedActions} — remediation steps tied to the candidates.</li>
 *   <li>{@code topologyPath} — ordered node names from the anomaly site to
 *       the inferred root.</li>
 * </ul>
 *
 * @param rootCauseCandidates ranked candidate root causes
 * @param confidence          confidence score (1.0 / 0.5 / 0.1)
 * @param suggestedActions    remediation steps
 * @param topologyPath        ordered node names from anomaly to root
 */
public record RootCauseReport(
        List<String> rootCauseCandidates,
        double confidence,
        List<String> suggestedActions,
        List<String> topologyPath) {
}
