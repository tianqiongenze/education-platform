package com.ep.data.aiops;

import java.util.Arrays;

import org.springframework.stereotype.Component;

/**
 * Pure-Java capacity forecaster using least-squares linear regression and
 * EWMA smoothing (§12.3.2).
 *
 * <p>Workflow:
 * <ol>
 *   <li>Optionally smooth the history with an EWMA filter.</li>
 *   <li>Fit a line {@code y = intercept + slope * x} by least squares.</li>
 *   <li>Extrapolate {@code forecastDays} points past the end of the series.</li>
 *   <li>Wrap each prediction in a ±2σ confidence band (95% CI).</li>
 * </ol>
 *
 * <p>Edge cases: empty history → empty forecast; single point → flat
 * forecast; all-zeros → flat zero forecast with zero variance.
 *
 * <p>Pure (no Spring/IO deps), instantiable with {@code new} in tests.
 */
@Component
public final class CapacityForecaster {

    /** EWMA alpha (smoothing factor); 0 &lt; alpha ≤ 1. */
    private static final double DEFAULT_ALPHA = 0.3;

    private final double alpha;

    /** Create a forecaster with the default EWMA alpha (0.3). */
    public CapacityForecaster() {
        this(DEFAULT_ALPHA);
    }

    /**
     * Create a forecaster with a custom EWMA alpha.
     *
     * @param alpha EWMA smoothing factor in (0, 1]
     */
    public CapacityForecaster(double alpha) {
        if (alpha <= 0.0 || alpha > 1.0 || Double.isNaN(alpha)) {
            alpha = DEFAULT_ALPHA;
        }
        this.alpha = alpha;
    }

    /**
     * Forecast future values for a metric.
     *
     * @param history      observed history (oldest first)
     * @param forecastDays number of future points to predict
     * @return forecast result (empty arrays for null/empty history)
     */
    public ForecastResult forecast(double[] history, int forecastDays) {
        if (history == null || history.length == 0 || forecastDays <= 0) {
            return new ForecastResult(new double[0], new double[0], new double[0], 0.0, 0.0);
        }
        if (history.length == 1) {
            double v = history[0];
            double[] pred = filled(forecastDays, v);
            double[] lo = filled(forecastDays, v);
            double[] hi = filled(forecastDays, v);
            return new ForecastResult(pred, lo, hi, 0.0, 0.0);
        }

        double[] smoothed = ewma(history);
        int n = smoothed.length;

        // Least-squares linear regression: x = index 0..n-1, y = smoothed[i].
        double sumX = 0.0, sumY = 0.0, sumXY = 0.0, sumX2 = 0.0;
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = smoothed[i];
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        double denom = n * sumX2 - sumX * sumX;
        double slope;
        double intercept;
        if (denom == 0.0) {
            slope = 0.0;
            intercept = sumY / n;
        } else {
            slope = (n * sumXY - sumX * sumY) / denom;
            intercept = (sumY - slope * sumX) / n;
        }

        // Residual standard deviation for the 95% CI (±2σ).
        double residualSumSquares = 0.0;
        double meanY = sumY / n;
        double totalSumSquares = 0.0;
        for (int i = 0; i < n; i++) {
            double fitted = intercept + slope * i;
            double resid = smoothed[i] - fitted;
            residualSumSquares += resid * resid;
            double tot = smoothed[i] - meanY;
            totalSumSquares += tot * tot;
        }
        double residualStddev = Math.sqrt(residualSumSquares / Math.max(1, n - 2));
        double rSquared = totalSumSquares == 0.0 ? 1.0 : 1.0 - residualSumSquares / totalSumSquares;

        double[] predicted = new double[forecastDays];
        double[] lower = new double[forecastDays];
        double[] upper = new double[forecastDays];
        for (int k = 0; k < forecastDays; k++) {
            double x = n + k;
            double y = intercept + slope * x;
            predicted[k] = y;
            lower[k] = y - 2.0 * residualStddev;
            upper[k] = y + 2.0 * residualStddev;
        }
        return new ForecastResult(predicted, lower, upper, slope, rSquared);
    }

    /**
     * Check whether a metric is projected to breach 80% of its capacity
     * limit within the forecast horizon.
     *
     * @param metricName    metric identifier
     * @param history       observed history
     * @param forecastDays  forecast horizon
     * @param capacityLimit hard capacity limit for the metric
     * @return a {@link CapacityAlert} if the predicted peak exceeds 80% of
     *         the limit; {@code null} otherwise
     */
    public CapacityAlert checkCapacity(String metricName, double[] history, int forecastDays, double capacityLimit) {
        if (metricName == null || capacityLimit <= 0.0) {
            return null;
        }
        ForecastResult fr = forecast(history, forecastDays);
        if (fr.predictedValues().length == 0) {
            return null;
        }
        double peak = Arrays.stream(fr.predictedValues()).max().orElse(Double.NEGATIVE_INFINITY);
        double thresholdPct = peak / capacityLimit;
        if (thresholdPct <= 0.8) {
            return null;
        }
        double current = (history == null || history.length == 0) ? 0.0 : history[history.length - 1];
        String alertType = classifyMetric(metricName);
        String suggestedAction = suggestAction(metricName, thresholdPct);
        return new CapacityAlert(
                metricName,
                current,
                peak,
                thresholdPct,
                alertType,
                suggestedAction);
    }

    /** Apply an EWMA filter to the history. */
    private double[] ewma(double[] history) {
        double[] out = new double[history.length];
        out[0] = history[0];
        for (int i = 1; i < history.length; i++) {
            out[i] = alpha * history[i] + (1.0 - alpha) * out[i - 1];
        }
        return out;
    }

    private static double[] filled(int len, double v) {
        double[] arr = new double[len];
        Arrays.fill(arr, v);
        return arr;
    }

    private static String classifyMetric(String metricName) {
        String m = metricName.toUpperCase();
        if (m.contains("STORAGE") || m.contains("DISK")) {
            return "STORAGE";
        }
        if (m.contains("CPU")) {
            return "CPU";
        }
        if (m.contains("MEMORY") || m.contains("MEM") || m.contains("HEAP")) {
            return "MEMORY";
        }
        if (m.contains("BACKLOG") || m.contains("LAG")) {
            return "BACKLOG";
        }
        return "OTHER";
    }

    private static String suggestAction(String metricName, double thresholdPct) {
        String m = metricName.toUpperCase();
        String pct = String.format("%.0f%%", thresholdPct * 100.0);
        if (m.contains("STORAGE") || m.contains("DISK")) {
            return "Projected to reach " + pct + " of storage capacity — schedule data retention cleanup or add nodes";
        }
        if (m.contains("CPU")) {
            return "Projected to reach " + pct + " of CPU capacity — scale out compute or throttle workloads";
        }
        if (m.contains("MEMORY") || m.contains("MEM")) {
            return "Projected to reach " + pct + " of memory capacity — increase TM heap or add TaskManagers";
        }
        if (m.contains("BACKLOG") || m.contains("LAG")) {
            return "Projected to reach " + pct + " of backlog capacity — scale consumers or check producer rate";
        }
        return "Projected to reach " + pct + " of capacity limit — investigate and scale accordingly";
    }
}
