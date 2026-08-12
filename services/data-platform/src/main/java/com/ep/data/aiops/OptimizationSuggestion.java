package com.ep.data.aiops;

/**
 * Scheduling optimization suggestion produced by
 * {@link ScheduleOptimizer} (§12.3.3).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code jobId} — Flink job the suggestion applies to.</li>
 *   <li>{@code currentConfig} — human-readable current configuration.</li>
 *   <li>{@code suggestedConfig} — human-readable suggested configuration.</li>
 *   <li>{@code estimatedCostSavingPct} — estimated cost delta (%). Negative
 *       values indicate an expected <em>cost increase</em> traded for SLA
 *       improvement.</li>
 *   <li>{@code reason} — human-readable rationale for the change.</li>
 * </ul>
 *
 * @param jobId                   Flink job the suggestion applies to
 * @param currentConfig           human-readable current configuration
 * @param suggestedConfig         human-readable suggested configuration
 * @param estimatedCostSavingPct  estimated cost delta (%)
 * @param reason                  human-readable rationale
 */
public record OptimizationSuggestion(
        String jobId,
        String currentConfig,
        String suggestedConfig,
        double estimatedCostSavingPct,
        String reason) {
}
