package com.ep.data.aiops;

/**
 * Capacity alert emitted when a forecasted metric is projected to exceed
 * 80% of its configured capacity limit (§12.3.2).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code metricName} — name of the monitored metric.</li>
 *   <li>{@code currentValue} — most recent observed value.</li>
 *   <li>{@code predictedValue} — forecasted value at the horizon end.</li>
 *   <li>{@code thresholdPct} — capacity-utilization percentage that
 *       triggered the alert (predictedValue / capacityLimit).</li>
 *   <li>{@code alertType} — short classification (e.g. {@code "STORAGE"},
 *       {@code "CPU"}, {@code "MEMORY"}).</li>
 *   <li>{@code suggestedAction} — human-readable remediation hint.</li>
 * </ul>
 *
 * @param metricName       name of the monitored metric
 * @param currentValue     most recent observed value
 * @param predictedValue   forecasted value at the horizon end
 * @param thresholdPct     predicted utilization as a fraction of the limit
 * @param alertType        short classification
 * @param suggestedAction  human-readable remediation hint
 */
public record CapacityAlert(
        String metricName,
        double currentValue,
        double predictedValue,
        double thresholdPct,
        String alertType,
        String suggestedAction) {
}
