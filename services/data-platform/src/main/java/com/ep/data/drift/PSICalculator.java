package com.ep.data.drift;

/**
 * Pure Java PSI (Population Stability Index) calculator (§12.2.2).
 *
 * <p>PSI quantifies distribution drift between a baseline and a current
 * sample. Implementation:
 * <ol>
 *   <li>Build quantile (equal-frequency) bucket boundaries from the
 *       baseline sample.</li>
 *   <li>Count baseline and current samples falling in each bucket.</li>
 *   <li>Convert counts to proportions: {@code p_baseline_i},
 *       {@code p_current_i}.</li>
 *   <li>{@code PSI = Σ (p_current_i - p_baseline_i) * ln(p_current_i / p_baseline_i)}.</li>
 * </ol>
 *
 * <p>Edge cases: empty arrays → 0.0; zero proportions → add small epsilon
 * (1e-6) to avoid {@code ln(0)} / division by zero.
 *
 * <p>This class is pure (no Spring/IO deps) and unit-testable standalone.
 */
public final class PSICalculator {

    private static final double EPSILON = 1e-6;
    private static final int DEFAULT_BUCKETS = 10;

    /**
     * Calculate PSI with the default 10 buckets.
     *
     * @param baseline baseline sample
     * @param current  current sample
     * @return PSI value (0.0 for empty/single-element inputs)
     */
    public double calculate(double[] baseline, double[] current) {
        return calculate(baseline, current, DEFAULT_BUCKETS);
    }

    /**
     * Calculate PSI with a custom bucket count.
     *
     * @param baseline baseline sample
     * @param current  current sample
     * @param buckets  number of quantile buckets
     * @return PSI value (0.0 for empty/single-element inputs)
     */
    public double calculate(double[] baseline, double[] current, int buckets) {
        if (baseline == null || current == null || baseline.length == 0 || current.length == 0) {
            return 0.0;
        }
        if (baseline.length < 2 || buckets < 1) {
            return 0.0;
        }

        double[] sortedBaseline = baseline.clone();
        java.util.Arrays.sort(sortedBaseline);

        int effectiveBuckets = Math.min(buckets, sortedBaseline.length);
        double[] boundaries = quantileBoundaries(sortedBaseline, effectiveBuckets);

        long[] baselineCounts = countByBucket(baseline, boundaries);
        long[] currentCounts = countByBucket(current, boundaries);

        double totalBaseline = baseline.length;
        double totalCurrent = current.length;

        double psi = 0.0;
        for (int i = 0; i < effectiveBuckets; i++) {
            double pBase = baselineCounts[i] / totalBaseline;
            double pCurr = currentCounts[i] / totalCurrent;
            if (pBase <= 0.0) {
                pBase = EPSILON;
            }
            if (pCurr <= 0.0) {
                pCurr = EPSILON;
            }
            psi += (pCurr - pBase) * Math.log(pCurr / pBase);
        }
        return psi;
    }

    /** Compute {@code n-1} interior quantile boundaries from a sorted baseline. */
    private static double[] quantileBoundaries(double[] sorted, int n) {
        if (n <= 1) {
            return new double[0];
        }
        double[] boundaries = new double[n - 1];
        for (int i = 1; i < n; i++) {
            int idx = (int) Math.round((double) i * sorted.length / n);
            if (idx >= sorted.length) {
                idx = sorted.length - 1;
            }
            boundaries[i - 1] = sorted[idx];
        }
        return boundaries;
    }

    /** Count samples in each bucket defined by the (interior) boundaries. */
    private static long[] countByBucket(double[] samples, double[] boundaries) {
        int n = boundaries.length + 1;
        long[] counts = new long[n];
        for (double v : samples) {
            int bucket = 0;
            for (int i = 0; i < boundaries.length; i++) {
                if (v > boundaries[i]) {
                    bucket = i + 1;
                } else {
                    break;
                }
            }
            counts[bucket]++;
        }
        return counts;
    }
}
