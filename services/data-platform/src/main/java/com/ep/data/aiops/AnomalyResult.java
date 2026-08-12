package com.ep.data.aiops;

import java.time.LocalDateTime;

/**
 * Result of an anomaly-detection scan over a single sample (§12.3.1).
 *
 * <p>{@code anomalyScore} is a normalized 0–1 value where higher means
 * <em>more anomalous</em>. The detector marks {@code isAnomaly = true} when
 * the score exceeds {@code 0.7}.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code metricIndex} — 0-based index of the sample within the input
 *       feature matrix (row index).</li>
 *   <li>{@code anomalyScore} — normalized anomaly score in {@code [0, 1]}.</li>
 *   <li>{@code isAnomaly} — {@code true} when {@code anomalyScore > 0.7}.</li>
 *   <li>{@code detectedAt} — wall-clock timestamp of the detection.</li>
 * </ul>
 *
 * @param metricIndex  sample row index in the feature matrix
 * @param anomalyScore normalized anomaly score in {@code [0, 1]}
 * @param isAnomaly    whether the score crossed the 0.7 threshold
 * @param detectedAt   detection timestamp
 */
public record AnomalyResult(
        int metricIndex,
        double anomalyScore,
        boolean isAnomaly,
        LocalDateTime detectedAt) {
}
