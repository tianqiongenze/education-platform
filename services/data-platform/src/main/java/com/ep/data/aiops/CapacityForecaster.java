package com.ep.data.aiops;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Pure-Java capacity forecaster using least-squares linear regression and
 * EWMA smoothing (§12.3.2), with an optional ONNX LSTM enhancement path
 * (D-189).
 *
 * <p><b>Two inference paths:</b>
 * <ul>
 *   <li><b>ONNX LSTM (D-189):</b> When an ONNX model is available via
 *       {@link ModelManager}, the forecaster feeds the EWMA-smoothed history
 *       into a pre-trained LSTM and produces multi-step predictions. This
 *       captures non-linear trends that linear regression cannot.</li>
 *   <li><b>Pure-Java EWMA + linear regression (fallback):</b> When no ONNX
 *       model is available (dev/CI, or production without a trained model),
 *       the forecaster uses the original least-squares approach. This is the
 *       default path in dev mode and is always available.</li>
 * </ul>
 *
 * <p>Workflow (pure-Java path):
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
 * <p>The pure-Java path is instantiable with {@code new CapacityForecaster()}
 * in tests. The ONNX path requires a {@link ModelManager} (injected via
 * {@link ObjectProvider} for graceful degradation).
 */
@Component
public final class CapacityForecaster {

    private static final Logger log = LoggerFactory.getLogger(CapacityForecaster.class);

    /** EWMA alpha (smoothing factor); 0 &lt; alpha ≤ 1. */
    private static final double DEFAULT_ALPHA = 0.3;

    /** LSTM input sequence length (number of time steps). */
    static final int LSTM_SEQ_LEN = 14;

    /** LSTM input feature dimension (1: univariate). */
    static final int LSTM_FEATURE_DIM = 1;

    private final double alpha;
    private final ObjectProvider<ModelManager> modelManagerProvider;

    /** Create a forecaster with the default EWMA alpha (0.3) — dev/CI mode. */
    public CapacityForecaster() {
        this(DEFAULT_ALPHA, null);
    }

    /**
     * Create a forecaster with a custom EWMA alpha.
     *
     * @param alpha EWMA smoothing factor in (0, 1]
     */
    public CapacityForecaster(double alpha) {
        this(alpha, null);
    }

    /**
     * Create a forecaster with a model manager provider (D-189 ONNX path).
     *
     * @param modelManagerProvider Spring {@link ObjectProvider} for
     *        {@link ModelManager}; may be null in tests
     */
    public CapacityForecaster(ObjectProvider<ModelManager> modelManagerProvider) {
        this(DEFAULT_ALPHA, modelManagerProvider);
    }

    /**
     * Create a forecaster with custom alpha and model manager.
     *
     * @param alpha                 EWMA smoothing factor
     * @param modelManagerProvider  ONNX model manager (null for pure-Java)
     */
    public CapacityForecaster(double alpha, ObjectProvider<ModelManager> modelManagerProvider) {
        if (alpha <= 0.0 || alpha > 1.0 || Double.isNaN(alpha)) {
            alpha = DEFAULT_ALPHA;
        }
        this.alpha = alpha;
        this.modelManagerProvider = modelManagerProvider;
    }

    /**
     * Forecast future values for a metric.
     *
     * <p>Attempts the ONNX LSTM path first; falls back to pure-Java EWMA +
     * linear regression when the model is unavailable.
     *
     * @param history      observed history (oldest first)
     * @param forecastDays number of future points to predict
     * @return forecast result (empty arrays for null/empty history)
     */
    public ForecastResult forecast(double[] history, int forecastDays) {
        if (history == null || history.length == 0 || forecastDays <= 0) {
            return new ForecastResult(new double[0], new double[0], new double[0], 0.0, 0.0);
        }
        // Try ONNX LSTM path.
        ForecastResult onnxResult = forecastWithLstm(history, forecastDays);
        if (onnxResult != null) {
            return onnxResult;
        }
        // Fall back to pure-Java.
        return forecastWithEwma(history, forecastDays);
    }

    /**
     * ONNX LSTM inference path (D-189).
     *
     * <p>Prepares the EWMA-smoothed history as a {@code [1][LSTM_SEQ_LEN][1]}
     * float tensor, runs the LSTM model, and extracts the predicted values.
     * Returns {@code null} when the model is unavailable or inference fails.
     *
     * @param history      observed history
     * @param forecastDays number of points to predict
     * @return forecast result, or {@code null} to fall back
     */
    ForecastResult forecastWithLstm(double[] history, int forecastDays) {
        ModelManager manager = resolveModelManager();
        if (manager == null || !manager.isAvailable(ModelManager.MODEL_CAPACITY_LSTM)) {
            return null;
        }
        OrtSession session = manager.getSession(ModelManager.MODEL_CAPACITY_LSTM);
        if (session == null) {
            return null;
        }
        try {
            // Prepare input: EWMA-smoothed, padded/truncated to LSTM_SEQ_LEN.
            double[] smoothed = ewma(history);
            float[] inputData = prepareLstmInput(smoothed);
            long[] shape = {1, LSTM_SEQ_LEN, LSTM_FEATURE_DIM};
            FloatBuffer buf = FloatBuffer.wrap(inputData);

            OrtEnvironment env = OrtEnvironment.getEnvironment();
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, buf, shape)) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                String inputName = session.getInputNames().iterator().next();
                inputs.put(inputName, inputTensor);
                try (OrtSession.Result result = session.run(inputs)) {
                    return extractLstmOutput(result, history, forecastDays);
                }
            }
        } catch (OrtException e) {
            log.warn("[CapacityForecaster] ONNX LSTM inference failed, falling back to EWMA: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[CapacityForecaster] ONNX LSTM path error, falling back to EWMA: {}", e.getMessage());
            return null;
        }
    }

    /** Prepare EWMA-smoothed history as a padded/truncated LSTM input. */
    private float[] prepareLstmInput(double[] smoothed) {
        float[] out = new float[LSTM_SEQ_LEN * LSTM_FEATURE_DIM];
        int offset = Math.max(0, smoothed.length - LSTM_SEQ_LEN);
        int copyLen = Math.min(smoothed.length, LSTM_SEQ_LEN);
        int dstStart = LSTM_SEQ_LEN - copyLen;
        for (int i = 0; i < copyLen; i++) {
            out[(dstStart + i) * LSTM_FEATURE_DIM] = (float) smoothed[offset + i];
        }
        return out;
    }

    /** Extract the LSTM output tensor and build a ForecastResult. */
    private ForecastResult extractLstmOutput(OrtSession.Result result, double[] history, int forecastDays) throws OrtException {
        Object value = result.get(0).getValue();
        float[] preds = null;
        if (value instanceof float[] farr) {
            preds = farr;
        } else if (value instanceof long[] larr) {
            preds = new float[larr.length];
            for (int i = 0; i < larr.length; i++) {
                preds[i] = (float) larr[i];
            }
        } else if (value instanceof double[] darr) {
            preds = new float[darr.length];
            for (int i = 0; i < darr.length; i++) {
                preds[i] = (float) darr[i];
            }
        }
        if (preds == null || preds.length == 0) {
            return null;
        }
        int n = Math.min(preds.length, forecastDays);
        double[] predicted = new double[n];
        double[] lower = new double[n];
        double[] upper = new double[n];
        // Compute residual stddev from history for confidence band.
        double residualStddev = computeResidualStddev(history);
        for (int i = 0; i < n; i++) {
            predicted[i] = preds[i];
            lower[i] = preds[i] - 2.0 * residualStddev;
            upper[i] = preds[i] + 2.0 * residualStddev;
        }
        // Slope and rSquared not meaningful for LSTM; set to 0.
        return new ForecastResult(predicted, lower, upper, 0.0, 0.0);
    }

    /** Compute a simple residual stddev from the history for CI bands. */
    private double computeResidualStddev(double[] history) {
        if (history == null || history.length < 3) {
            return 0.0;
        }
        double mean = Arrays.stream(history).average().orElse(0.0);
        double ss = 0.0;
        for (double v : history) {
            ss += (v - mean) * (v - mean);
        }
        return Math.sqrt(ss / (history.length - 1));
    }

    /**
     * Pure-Java EWMA + linear regression forecast (the original fallback).
     */
    ForecastResult forecastWithEwma(double[] history, int forecastDays) {
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

    /** Resolve the ModelManager from the ObjectProvider (null if unavailable). */
    private ModelManager resolveModelManager() {
        if (modelManagerProvider == null) {
            return null;
        }
        return modelManagerProvider.getIfAvailable();
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
