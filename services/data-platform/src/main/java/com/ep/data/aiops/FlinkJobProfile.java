package com.ep.data.aiops;

/**
 * Profile snapshot of a single Flink job used as input to
 * {@link ScheduleOptimizer} (§12.3.3).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code jobId} — Flink job identifier.</li>
 *   <li>{@code inputRecords} — total records ingested during the window.</li>
 *   <li>{@code durationMs} — wall-clock duration of the window (ms).</li>
 *   <li>{@code parallelism} — configured job parallelism.</li>
 *   <li>{@code tmCores} — TaskManager cores per slot.</li>
 *   <li>{@code tmMemoryMb} — TaskManager managed memory (MB).</li>
 *   <li>{@code backpressureRatio} — 0.0–1.0 backpressure ratio reported by
 *       the Flink Web UI.</li>
 * </ul>
 *
 * @param jobId              Flink job identifier
 * @param inputRecords       total records ingested during the window
 * @param durationMs         wall-clock duration of the window (ms)
 * @param parallelism        configured job parallelism
 * @param tmCores            TaskManager cores per slot
 * @param tmMemoryMb         TaskManager managed memory (MB)
 * @param backpressureRatio  0.0–1.0 backpressure ratio
 */
public record FlinkJobProfile(
        String jobId,
        long inputRecords,
        long durationMs,
        int parallelism,
        int tmCores,
        int tmMemoryMb,
        double backpressureRatio) {
}
