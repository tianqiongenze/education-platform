package com.ep.data.aiops;

import org.springframework.stereotype.Component;

/**
 * Flink job scheduler optimizer driven by a hand-written decision tree
 * (§12.3.3).
 *
 * <p>Rules (evaluated in priority order):
 * <ol>
 *   <li>{@code backpressureRatio > 0.5} → increase parallelism by 50%,
 *       estimated cost change -10% (cost increase, SLA improvement).</li>
 *   <li>{@code durationMs / inputRecords > 1.0} <em>and</em>
 *       {@code tmMemoryMb < 4096} → increase TM memory to 8192 MB.</li>
 *   <li>{@code backpressureRatio < 0.1} <em>and</em>
 *       {@code parallelism > 4} → decrease parallelism by 25%, +15% saving.</li>
 *   <li>{@code tmCores > 4} <em>and</em> {@code backpressureRatio < 0.1}
 *       → decrease TM cores to 4, +20% saving.</li>
 *   <li>Otherwise → no optimization needed.</li>
 * </ol>
 *
 * <p>Marked {@code @Component}; still a plain POJO instantiable with
 * {@code new} in tests.
 */
@Component
public final class ScheduleOptimizer {

    /**
     * Produce an optimization suggestion for a single Flink job profile.
     *
     * @param profile job profile (must not be null)
     * @return an {@link OptimizationSuggestion}
     */
    public OptimizationSuggestion optimize(FlinkJobProfile profile) {
        String current = describe(profile);
        if (profile == null) {
            return new OptimizationSuggestion("unknown", "n/a", "n/a", 0.0, "No profile provided");
        }

        double backpressure = profile.backpressureRatio();
        long inputRecords = profile.inputRecords();
        long durationMs = profile.durationMs();
        int parallelism = profile.parallelism();
        int tmCores = profile.tmCores();
        int tmMemoryMb = profile.tmMemoryMb();

        // Rule 1: high backpressure → scale out parallelism.
        if (backpressure > 0.5) {
            int newParallelism = Math.max(1, (int) Math.ceil(parallelism * 1.5));
            String suggested = describe(newParallelism, tmCores, tmMemoryMb);
            return new OptimizationSuggestion(
                    profile.jobId(),
                    current,
                    suggested,
                    -10.0,
                    "Backpressure ratio " + formatRatio(backpressure) + " > 0.5 — increase parallelism from "
                            + parallelism + " to " + newParallelism + " to relieve SLA risk (cost +10%)");
        }

        // Rule 2: high per-record latency and low memory → bump TM memory.
        if (inputRecords > 0 && ((double) durationMs / inputRecords) > 1.0 && tmMemoryMb < 4096) {
            String suggested = describe(parallelism, tmCores, 8192);
            double perRecord = (double) durationMs / inputRecords;
            return new OptimizationSuggestion(
                    profile.jobId(),
                    current,
                    suggested,
                    0.0,
                    "Per-record latency " + String.format("%.3f", perRecord)
                            + " ms/record > 1.0 with tmMemory " + tmMemoryMb
                            + "MB < 4096MB — increase TM memory to 8192MB");
        }

        // Rule 3: low backpressure and over-provisioned parallelism → scale in.
        if (backpressure < 0.1 && parallelism > 4) {
            int newParallelism = Math.max(1, (int) Math.floor(parallelism * 0.75));
            String suggested = describe(newParallelism, tmCores, tmMemoryMb);
            return new OptimizationSuggestion(
                    profile.jobId(),
                    current,
                    suggested,
                    15.0,
                    "Backpressure ratio " + formatRatio(backpressure) + " < 0.1 and parallelism "
                            + parallelism + " > 4 — decrease parallelism to " + newParallelism
                            + " (estimated 15% cost saving)");
        }

        // Rule 4: low backpressure and over-provisioned cores → trim cores.
        if (tmCores > 4 && backpressure < 0.1) {
            String suggested = describe(parallelism, 4, tmMemoryMb);
            return new OptimizationSuggestion(
                    profile.jobId(),
                    current,
                    suggested,
                    20.0,
                    "TM cores " + tmCores + " > 4 and backpressure " + formatRatio(backpressure)
                            + " < 0.1 — decrease TM cores to 4 (estimated 20% cost saving)");
        }

        // Rule 5: optimal.
        return new OptimizationSuggestion(
                profile.jobId(),
                current,
                current,
                0.0,
                "Optimal configuration — no optimization needed");
    }

    /** Build a human-readable config string for the current profile. */
    private static String describe(FlinkJobProfile p) {
        if (p == null) {
            return "n/a";
        }
        return describe(p.parallelism(), p.tmCores(), p.tmMemoryMb());
    }

    /** Build a human-readable config string from raw values. */
    private static String describe(int parallelism, int tmCores, int tmMemoryMb) {
        return "parallelism=" + parallelism + ", tmCores=" + tmCores + ", tmMemory=" + tmMemoryMb + "MB";
    }

    private static String formatRatio(double r) {
        return String.format("%.3f", r);
    }
}
