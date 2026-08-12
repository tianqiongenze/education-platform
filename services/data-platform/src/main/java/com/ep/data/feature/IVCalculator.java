package com.ep.data.feature;

import java.util.Arrays;

/**
 * Pure Java IV (Information Value) calculator (§12.2.1).
 *
 * <p>IV quantifies the predictive power of a feature with respect to a binary
 * target (good vs. bad). Implementation mirrors {@link com.ep.data.drift.PSICalculator}:
 * <ol>
 *   <li>Build equal-frequency (quantile) bucket boundaries from the good
 *       sample.</li>
 *   <li>Count good and bad samples falling in each bucket.</li>
 *   <li>Convert counts to proportions: {@code P_good_i}, {@code P_bad_i}.</li>
 *   <li>{@code IV = Σ (P_good_i - P_bad_i) * ln(P_good_i / P_bad_i)}.</li>
 * </ol>
 *
 * <p>Interpretation (SDD §12.2.1):
 * <ul>
 *   <li>{@code IV > 0.3} — strong predictive power (recommended candidate).</li>
 *   <li>{@code 0.1 <= IV <= 0.3} — medium.</li>
 *   <li>{@code IV < 0.1} — weak / unpredictive.</li>
 * </ul>
 *
 * <p>Edge cases: empty/null arrays → 0.0; single element → 0.0; zero
 * proportions → add epsilon (1e-6) to avoid {@code ln(0)} / division by zero.
 *
 * <p>This class is pure (no Spring/IO deps) and unit-testable standalone.
 */
public final class IVCalculator {

    private static final double EPSILON = 1e-6;
    private static final int DEFAULT_BUCKETS = 10;

    /** IV above this value indicates strong predictive power (§12.2.1). */
    public static final double STRONG_PREDICTIVE_THRESHOLD = 0.3;

    /**
     * Calculate IV with the default 10 buckets.
     *
     * @param goodSamples values for the "good" population
     * @param badSamples  values for the "bad" population
     * @return IV value (0.0 for empty/single-element inputs)
     */
    public double calculate(double[] goodSamples, double[] badSamples) {
        return calculate(goodSamples, badSamples, DEFAULT_BUCKETS);
    }

    /**
     * Calculate IV with a custom bucket count.
     *
     * @param goodSamples values for the "good" population
     * @param badSamples  values for the "bad" population
     * @param buckets     number of quantile buckets
     * @return IV value (0.0 for empty/single-element inputs)
     */
    public double calculate(double[] goodSamples, double[] badSamples, int buckets) {
        if (goodSamples == null || badSamples == null
                || goodSamples.length == 0 || badSamples.length == 0) {
            return 0.0;
        }
        if (goodSamples.length < 2 || buckets < 1) {
            return 0.0;
        }

        double[] sortedGood = goodSamples.clone();
        Arrays.sort(sortedGood);

        int effectiveBuckets = Math.min(buckets, sortedGood.length);
        double[] boundaries = quantileBoundaries(sortedGood, effectiveBuckets);

        long[] goodCounts = countByBucket(goodSamples, boundaries);
        long[] badCounts = countByBucket(badSamples, boundaries);

        double totalGood = goodSamples.length;
        double totalBad = badSamples.length;

        double iv = 0.0;
        for (int i = 0; i < effectiveBuckets; i++) {
            double pGood = goodCounts[i] / totalGood;
            double pBad = badCounts[i] / totalBad;
            if (pGood <= 0.0) {
                pGood = EPSILON;
            }
            if (pBad <= 0.0) {
                pBad = EPSILON;
            }
            iv += (pGood - pBad) * Math.log(pGood / pBad);
        }
        return iv;
    }

    /** Compute {@code n-1} interior quantile boundaries from a sorted sample. */
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
