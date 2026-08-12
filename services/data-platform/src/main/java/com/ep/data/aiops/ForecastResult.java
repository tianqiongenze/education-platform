package com.ep.data.aiops;

/**
 * Result of a capacity forecast (§12.3.2).
 *
 * <p>The forecaster fits a least-squares linear regression on the history,
 * extrapolates {@code forecastDays} points along the trend, and wraps each
 * prediction in a ±2σ confidence band (95% CI approximation).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code predictedValues} — extrapolated future values.</li>
 *   <li>{@code confidenceLower} — lower bound of the 95% CI per step.</li>
 *   <li>{@code confidenceUpper} — upper bound of the 95% CI per step.</li>
 *   <li>{@code slope} — fitted slope (units/day).</li>
 *   <li>{@code rSquared} — goodness-of-fit of the linear model (0–1).</li>
 * </ul>
 *
 * @param predictedValues  extrapolated future values
 * @param confidenceLower  lower bound of the 95% CI per step
 * @param confidenceUpper  upper bound of the 95% CI per step
 * @param slope            fitted slope (units/day)
 * @param rSquared         goodness-of-fit of the linear model (0–1)
 */
public record ForecastResult(
        double[] predictedValues,
        double[] confidenceLower,
        double[] confidenceUpper,
        double slope,
        double rSquared) {
}
