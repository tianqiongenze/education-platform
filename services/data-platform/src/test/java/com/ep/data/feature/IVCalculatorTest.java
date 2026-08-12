package com.ep.data.feature;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link IVCalculator}.
 */
class IVCalculatorTest {

    private final IVCalculator calculator = new IVCalculator();

    private static double[] normal(double mean, double sd, int n, long seed) {
        Random r = new Random(seed);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = mean + sd * r.nextGaussian();
        }
        return out;
    }

    @Test
    void test_high_iv_strong_predictive() {
        // Good samples clustered low, bad samples clustered high → strong separation.
        double[] good = normal(0, 1, 500, 1L);
        double[] bad = normal(5, 1, 500, 2L);
        double iv = calculator.calculate(good, bad);
        assertTrue(iv > IVCalculator.STRONG_PREDICTIVE_THRESHOLD,
                "distinct distributions should have IV > 0.3, got " + iv);
    }

    @Test
    void test_low_iv_weak_predictive() {
        // Same distribution → IV near 0.
        double[] good = normal(0, 1, 500, 3L);
        double[] bad = normal(0, 1, 500, 4L);
        double iv = calculator.calculate(good, bad);
        assertTrue(iv < 0.1, "similar distributions should have IV < 0.1, got " + iv);
    }

    @Test
    void test_empty_arrays_return_zero() {
        assertEquals(0.0, calculator.calculate(new double[0], new double[]{1, 2}),
                "empty good → 0.0");
        assertEquals(0.0, calculator.calculate(new double[]{1, 2}, new double[0]),
                "empty bad → 0.0");
        assertEquals(0.0, calculator.calculate(null, new double[]{1, 2}),
                "null good → 0.0");
    }

    @Test
    void test_single_element_returns_zero() {
        assertEquals(0.0, calculator.calculate(new double[]{1.0}, new double[]{2.0}),
                "single element good → 0.0");
    }

    @Test
    void test_different_bucket_counts() {
        double[] good = normal(0, 1, 500, 5L);
        double[] bad = normal(3, 1, 500, 6L);
        double iv5 = calculator.calculate(good, bad, 5);
        double iv10 = calculator.calculate(good, bad, 10);
        assertTrue(iv5 > 0.0, "5-bucket IV should be > 0 for separated dists, got " + iv5);
        assertTrue(iv10 > 0.0, "10-bucket IV should be > 0 for separated dists, got " + iv10);
    }

    @Test
    void test_epsilon_boundary() {
        // All identical values → every sample in one bucket, no division by zero.
        double[] good = new double[]{5.0, 5.0, 5.0, 5.0};
        double[] bad = new double[]{5.0, 5.0, 5.0, 5.0};
        double iv = calculator.calculate(good, bad);
        assertEquals(0.0, iv, 1e-9, "all-same values → IV 0.0, got " + iv);
    }

    @Test
    void test_strong_predictive_threshold_constant() {
        assertEquals(0.3, IVCalculator.STRONG_PREDICTIVE_THRESHOLD,
                "STRONG_PREDICTIVE_THRESHOLD should be 0.3");
    }
}
