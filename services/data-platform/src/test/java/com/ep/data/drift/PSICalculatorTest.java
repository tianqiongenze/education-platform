package com.ep.data.drift;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link PSICalculator} and {@link DriftDetector}.
 */
class PSICalculatorTest {

    private final PSICalculator calculator = new PSICalculator();
    private final DriftDetector detector = new DriftDetector();

    private static double[] normal(double mean, double sd, int n) {
        return normal(mean, sd, n, 42L);
    }

    /** Deterministic normal sample with a custom seed (so drift tests are reproducible). */
    private static double[] normal(double mean, double sd, int n, long seed) {
        java.util.Random r = new java.util.Random(seed);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = mean + sd * r.nextGaussian();
        }
        return out;
    }

    @Test
    void test_identical_distributions_psi_near_zero() {
        double[] base = new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20};
        double[] curr = base.clone();
        double psi = calculator.calculate(base, curr);
        assertTrue(psi < 0.01, "PSI for identical distributions should be ~0, got " + psi);
    }

    @Test
    void test_slight_shift_psi_in_alert_range() {
        double[] base = normal(100, 10, 2000);
        double[] curr = normal(104, 10, 2000, 7L); // moderate shift, fixed seed → PSI ≈ 0.18
        double psi = calculator.calculate(base, curr);
        assertTrue(psi > 0.05 && psi < 0.3,
                "PSI for slight/moderate shift should be in (0.05, 0.3), got " + psi);
    }

    @Test
    void test_major_shift_psi_severe() {
        double[] base = normal(100, 10, 2000);
        double[] curr = normal(150, 10, 2000, 99L); // big mean shift, fixed seed
        double psi = calculator.calculate(base, curr);
        assertTrue(psi > 0.4, "PSI for major shift should be > 0.4, got " + psi);
    }

    @Test
    void test_empty_arrays_return_zero() {
        assertEquals(0.0, calculator.calculate(new double[0], new double[]{1, 2}));
        assertEquals(0.0, calculator.calculate(new double[]{1, 2}, new double[0]));
        assertEquals(0.0, calculator.calculate(null, new double[]{1, 2}));
    }

    @Test
    void test_different_bucket_counts_produce_valid_results() {
        double[] base = normal(0, 1, 500);
        double[] curr = normal(2, 1, 500, 13L);
        double psi5 = calculator.calculate(base, curr, 5);
        double psi10 = calculator.calculate(base, curr, 10);
        assertTrue(psi5 > 0.0, "5-bucket PSI should be > 0 for shifted dist, got " + psi5);
        assertTrue(psi10 > 0.0, "10-bucket PSI should be > 0 for shifted dist, got " + psi10);
    }

    @Test
    void test_single_element_arrays_return_zero() {
        assertEquals(0.0, calculator.calculate(new double[]{1.0}, new double[]{2.0}));
    }

    @Test
    void test_drift_detector_alert_severe_classification() {
        double[] base = normal(100, 10, 2000);
        double[] identical = base.clone();
        // Moderate shift (mean 100 → 105) crosses the 0.2 alert threshold but
        // stays below 0.4 severe. Seed 7 → deterministic PSI ≈ 0.28.
        double[] moderate = normal(105, 10, 2000, 7L);
        double[] major = normal(150, 10, 2000, 99L);

        DriftResult ok = detector.detect("f1", base, identical);
        assertFalse(ok.isAlert(), "identical distribution should not alert");
        assertFalse(ok.isSevere());

        DriftResult alert = detector.detect("f2", base, moderate);
        assertTrue(alert.isAlert(), "moderate shift should alert, psi=" + alert.psi());
        assertFalse(alert.isSevere(), "moderate shift should not be severe, psi=" + alert.psi());

        DriftResult severe = detector.detect("f3", base, major);
        assertTrue(severe.isAlert(), "major shift should alert");
        assertTrue(severe.isSevere(), "major shift should be severe");
        assertEquals(0.2, severe.threshold());
    }
}
