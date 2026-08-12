package com.ep.data.aiops;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.springframework.stereotype.Component;

/**
 * Pure-Java anomaly detector combining a simplified Isolation Forest and a
 * 3-Sigma rule (§12.3.1).
 *
 * <p><b>Simplified Isolation Forest.</b> For each sample we build a single
 * random isolation tree: at every level we pick a feature dimension at
 * random, pick a split point uniformly between the min/max of that
 * dimension, and recurse on the side containing the sample until the depth
 * limit is reached. The path length is the number of splits traversed.
 * Shorter paths indicate points that are easy to isolate and therefore more
 * anomalous. The raw path length is normalized to a {@code [0, 1]} score
 * where higher = more anomalous, using the standard
 * {@code c(n) = 2 * H(n-1) - 2*(n-1)/n} average-path normalization.
 *
 * <p><b>3-Sigma rule.</b> For a 1-D series, a point is anomalous when
 * {@code |x - mean| > 3 * stddev}.
 *
 * <p>Score > {@link #ANOMALY_THRESHOLD} (0.7) marks a sample as anomalous.
 *
 * <p>This class is pure (no Spring/IO deps) and instantiable with
 * {@code new AnomalyDetector()} for unit tests, mirroring
 * {@code PSICalculator}. A fixed seed makes results reproducible.
 */
@Component
public final class AnomalyDetector {

    /** Score threshold above which a sample is flagged as an anomaly. */
    public static final double ANOMALY_THRESHOLD = 0.7;

    /** Number of trees averaged per detection pass. */
    private static final int NUM_TREES = 100;

    /** Maximum isolation-tree depth (capped at ceil(log2(n))). */
    private static final int MAX_DEPTH_LIMIT = 16;

    private final Random random;
    private final int numTrees;

    /**
     * Create a detector with the default tree count and a fixed seed for
     * deterministic output in tests.
     */
    public AnomalyDetector() {
        this(NUM_TREES, 0x5EEDL);
    }

    /**
     * Create a detector with a custom tree count and seed.
     *
     * @param numTrees number of isolation trees to average
     * @param seed     PRNG seed (use a fixed value for deterministic tests)
     */
    public AnomalyDetector(int numTrees, long seed) {
        this.numTrees = Math.max(1, numTrees);
        this.random = new Random(seed);
    }

    /**
     * Detect anomalies in a {@code [sampleCount][4]} feature matrix.
     *
     * <p>Feature columns are, in order: CDC latency (ms), Flink checkpoint
     * duration (ms), Iceberg compaction frequency (per hour), Trino P99
     * query latency (ms).
     *
     * @param features {@code [n][4]} feature matrix; may be null/empty
     * @return one {@link AnomalyResult} per sample (empty for null/empty input)
     */
    public List<AnomalyResult> detect(double[][] features) {
        List<AnomalyResult> results = new ArrayList<>();
        if (features == null || features.length == 0) {
            return results;
        }
        if (features.length == 1) {
            // Cannot isolate a single sample; score it as non-anomalous.
            results.add(new AnomalyResult(0, 0.0, false, LocalDateTime.now()));
            return results;
        }

        int n = features.length;
        int dims = features[0].length;
        int maxDepth = Math.min(MAX_DEPTH_LIMIT, (int) Math.ceil(log2(n)));

        double[] avgPathLengths = new double[n];
        for (int t = 0; t < numTrees; t++) {
            for (int i = 0; i < n; i++) {
                avgPathLengths[i] += pathLength(features, i, dims, maxDepth, random);
            }
        }
        for (int i = 0; i < n; i++) {
            avgPathLengths[i] /= numTrees;
        }

        LocalDateTime now = LocalDateTime.now();
        // Standard Isolation Forest normalization constant c(n).
        double normalization = cFactor(n);

        for (int i = 0; i < n; i++) {
            double raw = avgPathLengths[i];
            // Normalized anomaly score per Isolation Forest: s = 2^(-E(h)/c(n))
            // shorter path → larger s → more anomalous. For n ≈ 80, c(n) ≈ 10,
            // so a sample at max-depth (~6) scores ~0.66 while a sample that
            // isolates in 1–2 splits scores > 0.9.
            double score;
            if (normalization <= 0.0) {
                score = 0.0;
            } else {
                score = Math.pow(2.0, -raw / normalization);
            }
            if (Double.isNaN(score) || score < 0.0) {
                score = 0.0;
            }
            if (score > 1.0) {
                score = 1.0;
            }
            boolean isAnomaly = score > ANOMALY_THRESHOLD;
            results.add(new AnomalyResult(i, score, isAnomaly, now));
        }
        return results;
    }

    /**
     * 3-Sigma rule for a 1-D series: returns one boolean per input value,
     * {@code true} when {@code |x - mean| > 3 * stddev}.
     *
     * @param values 1-D series; may be null/empty
     * @return boolean flags (empty for null/empty input)
     */
    public boolean[] detectSigma(double[] values) {
        if (values == null || values.length == 0) {
            return new boolean[0];
        }
        boolean[] flags = new boolean[values.length];
        if (values.length == 1) {
            // stddev is undefined; single sample is never anomalous.
            flags[0] = false;
            return flags;
        }
        double mean = 0.0;
        for (double v : values) {
            mean += v;
        }
        mean /= values.length;
        double variance = 0.0;
        for (double v : values) {
            double d = v - mean;
            variance += d * d;
        }
        variance /= values.length;
        double stddev = Math.sqrt(variance);
        if (stddev == 0.0) {
            // All values identical → no anomaly possible.
            return flags;
        }
        for (int i = 0; i < values.length; i++) {
            flags[i] = Math.abs(values[i] - mean) > 3.0 * stddev;
        }
        return flags;
    }

    /**
     * Compute the isolation path length for a single sample by recursively
     * splitting a random feature at a random point.
     *
     * <p>At each level we pick a feature dimension at random and a split
     * point uniformly between the current min/max of that dimension (over
     * the live sample population). We follow the branch containing the
     * sample of interest and <em>also</em> narrow the live population to
     * that branch, so points in sparse regions get isolated (path
     * terminates early because the live population collapses to a single
     * sample) while points in dense regions keep splitting until
     * {@code maxDepth}.
     *
     * @param features full feature matrix
     * @param index    row index of the sample being isolated
     * @param dims     number of feature dimensions
     * @param maxDepth tree depth cap
     * @param rng      PRNG used for dimension/split selection
     * @return path length (number of splits traversed)
     */
    private static int pathLength(double[][] features, int index, int dims, int maxDepth, Random rng) {
        int depth = 0;
        int n = features.length;
        // Live population: indices of samples still in the same branch.
        java.util.List<int[]> activePerDim = new java.util.ArrayList<>();
        // Track min/max per dim over the live population, plus the count of
        // distinct samples still in the branch (used to detect isolation).
        double[] min = new double[dims];
        double[] max = new double[dims];
        boolean[] live = new boolean[n];
        java.util.Arrays.fill(live, true);
        int liveCount = n;

        recomputeBounds(features, live, dims, min, max);

        while (depth < maxDepth && liveCount > 1) {
            int dim = rng.nextInt(dims);
            if (min[dim] >= max[dim]) {
                // No spread on this dim; try a different one by advancing.
                // To avoid infinite loops, break if all dims are degenerate.
                boolean anySpread = false;
                for (int d = 0; d < dims; d++) {
                    if (max[d] > min[d]) {
                        anySpread = true;
                        break;
                    }
                }
                if (!anySpread) {
                    break;
                }
                continue;
            }
            double split = min[dim] + rng.nextDouble() * (max[dim] - min[dim]);
            double v = features[index][dim];
            boolean goLeft = v <= split;
            int newLive = 0;
            for (int i = 0; i < n; i++) {
                if (!live[i]) {
                    continue;
                }
                double iv = features[i][dim];
                boolean keep = goLeft ? (iv <= split) : (iv > split);
                if (!keep) {
                    live[i] = false;
                } else {
                    newLive++;
                }
            }
            liveCount = newLive;
            recomputeBounds(features, live, dims, min, max);
            depth++;
        }
        return depth;
    }

    /** Recompute per-dimension min/max over the currently live samples. */
    private static void recomputeBounds(double[][] features, boolean[] live, int dims, double[] min, double[] max) {
        for (int d = 0; d < dims; d++) {
            min[d] = Double.POSITIVE_INFINITY;
            max[d] = Double.NEGATIVE_INFINITY;
        }
        for (int i = 0; i < features.length; i++) {
            if (!live[i]) {
                continue;
            }
            for (int d = 0; d < dims && d < features[i].length; d++) {
                double v = features[i][d];
                if (v < min[d]) {
                    min[d] = v;
                }
                if (v > max[d]) {
                    max[d] = v;
                }
            }
        }
    }

    /**
     * Average path length of an unsuccessful search in a BST with n nodes,
     * used to normalize isolation-tree path lengths.
     *
     * @param n number of samples
     * @return normalization constant c(n)
     */
    private static double cFactor(int n) {
        if (n <= 1) {
            return 0.0;
        }
        return 2.0 * harmonic(n - 1) - 2.0 * (n - 1) / n;
    }

    /** Harmonic number H(k) approximated with the digamma-free series. */
    private static double harmonic(int k) {
        if (k <= 0) {
            return 0.0;
        }
        double h = 0.0;
        for (int i = 1; i <= k; i++) {
            h += 1.0 / i;
        }
        return h;
    }

    private static double log2(double v) {
        return Math.log(v) / Math.log(2.0);
    }
}
