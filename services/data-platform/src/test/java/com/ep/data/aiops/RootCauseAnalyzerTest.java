package com.ep.data.aiops;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link RootCauseAnalyzer}.
 */
class RootCauseAnalyzerTest {

    private final RootCauseAnalyzer analyzer = new RootCauseAnalyzer();

    @Test
    void test_single_anomaly_root_cause() {
        // Single CDC_DELAY anomaly → topology path should start at CDC.
        RootCauseReport report = analyzer.analyze(Set.of("CDC_DELAY"));
        assertNotNull(report, "report must not be null");
        assertFalse(report.topologyPath().isEmpty(),
                "topology path must not be empty for a known metric, got " + report.topologyPath());
        assertEquals("CDC", report.topologyPath().get(0),
                "path should start at the CDC root, got " + report.topologyPath());
        assertTrue(report.confidence() >= 0.5,
                "single known metric should have confidence >= 0.5, got " + report.confidence());
    }

    @Test
    void test_multi_anomaly_association_rule() {
        RootCauseReport report = analyzer.analyze(Set.of("CDC_DELAY", "FLINK_CHECKPOINT_FAIL"));
        assertNotNull(report);
        assertEquals(1.0, report.confidence(),
                "association-rule match should yield confidence 1.0, got " + report.confidence());
        assertFalse(report.rootCauseCandidates().isEmpty(),
                "candidates must not be empty for a rule match");
        String candidate = report.rootCauseCandidates().get(0);
        assertTrue(candidate.contains("TiDB GC stall"),
                "candidate should mention TiDB GC stall, got: " + candidate);
        assertFalse(report.suggestedActions().isEmpty(),
                "suggested actions must not be empty for a rule match");
    }

    @Test
    void test_topology_path_traversal() {
        // Anomaly at Trino (downstream) → BFS should walk back through Iceberg/Flink/Pulsar/CDC.
        RootCauseReport report = analyzer.analyze(Set.of("TRINO_P99"));
        List<String> path = report.topologyPath();
        assertFalse(path.isEmpty(), "topology path must not be empty for TRINO_P99");
        assertEquals("CDC", path.get(0),
                "BFS from Trino should reach upstream root CDC first, got " + path);
        assertEquals("TRINO", path.get(path.size() - 1),
                "path should end at the anomaly site TRINO, got " + path);
        assertTrue(path.contains("ICEBERG") && path.contains("FLINK") && path.contains("PULSAR"),
                "path should traverse all intermediate nodes, got " + path);
    }

    @Test
    void test_unknown_anomaly_low_confidence() {
        RootCauseReport report = analyzer.analyze(Set.of("SOME_UNKNOWN_METRIC"));
        assertNotNull(report);
        assertEquals(0.1, report.confidence(),
                "unknown metric should yield confidence 0.1, got " + report.confidence());
        assertTrue(report.topologyPath().isEmpty(),
                "unknown metric should produce no topology path, got " + report.topologyPath());
    }

    @Test
    void test_empty_input_returns_empty() {
        RootCauseReport report = analyzer.analyze(Set.of());
        assertNotNull(report);
        assertTrue(report.rootCauseCandidates().isEmpty(),
                "empty input → no candidates, got " + report.rootCauseCandidates());
        assertEquals(0.0, report.confidence(), "empty input → confidence 0.0, got " + report.confidence());
        assertTrue(report.topologyPath().isEmpty(),
                "empty input → no topology path, got " + report.topologyPath());

        RootCauseReport nullReport = analyzer.analyze(null);
        assertNotNull(nullReport, "null input should still return a non-null report");
        assertTrue(nullReport.rootCauseCandidates().isEmpty(),
                "null input → no candidates, got " + nullReport.rootCauseCandidates());
    }

    @Test
    void test_pulsar_flink_association_rule() {
        RootCauseReport report = analyzer.analyze(Set.of("PULSAR_BACKLOG", "FLINK_CHECKPOINT_FAIL"));
        assertEquals(1.0, report.confidence(),
                "PULSAR+FLINK rule should match with confidence 1.0, got " + report.confidence());
        assertTrue(report.rootCauseCandidates().get(0).contains("Pulsar broker"),
                "candidate should mention Pulsar broker, got: " + report.rootCauseCandidates().get(0));
    }
}
