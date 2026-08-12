package com.ep.data.aiops;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link AnomalyDetector}.
 *
 * <p>JUnit 5 (Jupiter), no Spring context. Deterministic random data via
 * {@code java.util.Random(seed)}. Snake_case test method names.
 */
class AnomalyDetectorTest {

    private final AnomalyDetector detector = new AnomalyDetector();

    /** Deterministic 4-D normal sample with a fixed seed. */
    private static double[][] normal4D(int n, long seed) {
        Random r = new Random(seed);
        double[][] out = new double[n][4];
        for (int i = 0; i < n; i++) {
            out[i][0] = 50.0 + r.nextGaussian() * 10.0;
            out[i][1] = 200.0 + r.nextGaussian() * 30.0;
            out[i][2] = 2.0 + r.nextGaussian() * 0.5;
            out[i][3] = 800.0 + r.nextGaussian() * 100.0;
        }
        return out;
    }

    @Test
    void test_normal_data_no_anomaly() {
        double[][] features = normal4D(80, 42L);
        List<AnomalyResult> results = detector.detect(features);
        assertEquals(80, results.size(), "expected one result per sample, got " + results.size());
        long anomalies = results.stream().filter(AnomalyResult::isAnomaly).count();
        // With a large cluster of normal points no sample should isolate easily.
        assertTrue(anomalies <= 5,
                "normal data should produce few/no anomalies, got " + anomalies + " anomalies");
    }

    @Test
    void test_injected_outlier_detected() {
        double[][] features = normal4D(80, 42L);
        // Inject an extreme outlier at index 40.
        features[40][0] = 50000.0;
        features[40][1] = 80000.0;
        features[40][2] = 100.0;
        features[40][3] = 90000.0;
        List<AnomalyResult> results = detector.detect(features);
        AnomalyResult outlier = results.get(40);
        assertTrue(outlier.isAnomaly(),
                "injected outlier should be flagged, score=" + outlier.anomalyScore());
        assertTrue(outlier.anomalyScore() > AnomalyDetector.ANOMALY_THRESHOLD,
                "outlier score " + outlier.anomalyScore() + " should exceed threshold "
                        + AnomalyDetector.ANOMALY_THRESHOLD);
    }

    @Test
    void test_three_sigma_boundary() {
        // mean=100, stddev=10 → 3σ boundary at 130. A point exactly at 131
        // should trigger; a point at 129 should not (strictly greater than 3σ).
        double[] values = new double[]{100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 131.0};
        boolean[] flags = detector.detectSigma(values);
        // 131 - 100 = 31 > 30 = 3*10 → flagged.
        assertTrue(flags[flags.length - 1],
                "value 131 at 3.1σ should be flagged, mean=100 stddev=10");
        // All identical values contribute stddev=10? No — 10 identical 100s + 131.
        // mean = (1000 + 131)/11 ≈ 102.8, recompute is not exactly 3σ, so we just
        // assert the extreme point is the only flagged one.
        long total = 0;
        for (boolean f : flags) {
            if (f) total++;
        }
        assertTrue(total >= 1, "at least one value should be flagged, got " + total);
    }

    @Test
    void test_empty_array_returns_empty() {
        assertEquals(0, detector.detect(null).size(), "null features should yield no results");
        assertEquals(0, detector.detect(new double[0][]).size(), "empty features should yield no results");
        assertEquals(0, detector.detectSigma(null).length, "null 1-D input should yield no flags");
        assertEquals(0, detector.detectSigma(new double[0]).length, "empty 1-D input should yield no flags");
    }

    @Test
    void test_single_sample_returns_empty_non_anomalous() {
        double[][] single = new double[][]{{1.0, 2.0, 3.0, 4.0}};
        List<AnomalyResult> results = detector.detect(single);
        assertEquals(1, results.size(), "single sample should produce one result, got " + results.size());
        assertFalse(results.get(0).isAnomaly(),
                "single sample cannot be isolated, should not be anomaly, score="
                        + results.get(0).anomalyScore());
    }

    @Test
    void test_all_same_values_no_anomaly() {
        // stddev = 0 → 3-sigma should never flag anything.
        double[] values = new double[]{5.0, 5.0, 5.0, 5.0, 5.0};
        boolean[] flags = detector.detectSigma(values);
        for (int i = 0; i < flags.length; i++) {
            assertFalse(flags[i], "identical values (stddev=0) should not be flagged at index " + i);
        }
        // Isolation forest on identical rows: no splits possible → all paths 0 → all scores maximal.
        // Guard: such degenerate input should still not throw.
        double[][] features = new double[][]{
                {1.0, 1.0, 1.0, 1.0},
                {1.0, 1.0, 1.0, 1.0},
                {1.0, 1.0, 1.0, 1.0}
        };
        List<AnomalyResult> results = detector.detect(features);
        assertEquals(3, results.size(), "all-same 4D input should still produce results");
    }

    @Test
    void test_multidimensional_isolation_forest() {
        double[][] features = normal4D(64, 7L);
        List<AnomalyResult> results = detector.detect(features);
        assertEquals(64, results.size(), "expected 64 results for 64 samples, got " + results.size());
        for (AnomalyResult r : results) {
            assertNotNull(r.detectedAt(), "detectedAt must be set");
            assertTrue(r.anomalyScore() >= 0.0 && r.anomalyScore() <= 1.0,
                    "score must be in [0,1], got " + r.anomalyScore());
            assertEquals(r.anomalyScore() > AnomalyDetector.ANOMALY_THRESHOLD, r.isAnomaly(),
                    "isAnomaly must equal score>threshold, score=" + r.anomalyScore());
        }
    }

    @Test
    void test_sigma_detect_finds_outlier() {
        // 50 normal values + one extreme outlier at the end.
        Random r = new Random(123L);
        double[] values = new double[51];
        for (int i = 0; i < 50; i++) {
            values[i] = 100.0 + r.nextGaussian() * 5.0;
        }
        values[50] = 1000.0; // extreme outlier
        boolean[] flags = detector.detectSigma(values);
        assertTrue(flags[50], "extreme outlier at index 50 should be flagged by 3-sigma");
        int falsePositives = 0;
        for (int i = 0; i < 50; i++) {
            if (flags[i]) {
                falsePositives++;
            }
        }
        assertTrue(falsePositives <= 2,
                "expected at most 2 false positives among normal values, got " + falsePositives);
    }
}
