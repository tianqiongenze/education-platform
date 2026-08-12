package com.ep.data.aiops;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for AIOps predictive operations (§12.3).
 *
 * <p>Exposes four capabilities:
 * <ul>
 *   <li>{@code GET /api/v1/aiops/anomalies} — anomaly detection over recent
 *       ops metrics (CDC latency, Flink checkpoint, compaction freq, Trino
 *       P99).</li>
 *   <li>{@code GET /api/v1/aiops/capacity/forecast} — linear-regression
 *       capacity forecast for a named metric.</li>
 *   <li>{@code POST /api/v1/aiops/root-cause} — topology DAG root-cause
 *       analysis from a set of anomalous metrics.</li>
 *   <li>{@code POST /api/v1/aiops/schedule/optimize} — Flink job scheduling
 *       optimization suggestion.</li>
 * </ul>
 *
 * <p>The {@code GET} endpoints that would need real Prometheus/Iceberg data
 * are guarded by the {@code EP_DEV_ONLY} environment variable: when the
 * guard is active (unset, blank, or {@code "true"}) the controller returns
 * simulated data so the service is fully functional in dev/CI without a
 * live cluster. Production deployments set {@code EP_DEV_ONLY=false} and
 * wire in real metric sources.
 */
@RestController
@RequestMapping("/api/v1/aiops")
@Tag(name = "AIOps预测性运维", description = "异常检测+容量预测+根因分析+调度优化")
public class AiopsController {

    private static final Logger log = LoggerFactory.getLogger(AiopsController.class);

    /** Dev/CI fallback store for capacity forecast history, keyed by metric name. */
    private final Map<String, double[]> capacityHistoryStore = new ConcurrentHashMap<>();

    private final AnomalyDetector anomalyDetector;
    private final CapacityForecaster capacityForecaster;
    private final RootCauseAnalyzer rootCauseAnalyzer;
    private final ScheduleOptimizer scheduleOptimizer;

    public AiopsController(AnomalyDetector anomalyDetector,
                           CapacityForecaster capacityForecaster,
                           RootCauseAnalyzer rootCauseAnalyzer,
                           ScheduleOptimizer scheduleOptimizer) {
        this.anomalyDetector = anomalyDetector;
        this.capacityForecaster = capacityForecaster;
        this.rootCauseAnalyzer = rootCauseAnalyzer;
        this.scheduleOptimizer = scheduleOptimizer;
    }

    /**
     * Detect anomalies in recent ops metrics.
     *
     * <p>In dev/CI (EP_DEV_ONLY active) the endpoint synthesizes a
     * {@code [60][4]} feature matrix with normal values plus an injected
     * outlier so the pipeline is exercisable without Prometheus. In
     * production it reads from {@code ops.metrics_history}.
     *
     * @return one {@link AnomalyResult} per sample
     */
    @GetMapping("/anomalies")
    public ResponseEntity<List<AnomalyResult>> anomalies() {
        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);

        double[][] features;
        if (devGuard) {
            log.info("[AiopsController] EP_DEV_ONLY active — synthesizing anomaly-detection features");
            features = synthesizeAnomalyFeatures();
        } else {
            // Production: read from ops.metrics_history (not implemented in MVP).
            log.info("[AiopsController] Production mode — reading features from ops.metrics_history");
            features = loadProductionFeatures();
        }
        List<AnomalyResult> results = anomalyDetector.detect(features);
        return ResponseEntity.ok(results);
    }

    /**
     * Forecast future values for a named capacity metric.
     *
     * @param metric metric name (e.g. {@code storage}, {@code cpu})
     * @param days   forecast horizon in days (default 30)
     * @return a {@link ForecastResult}
     */
    @GetMapping("/capacity/forecast")
    public ResponseEntity<ForecastResult> capacityForecast(
            @RequestParam(name = "metric", defaultValue = "storage") String metric,
            @RequestParam(name = "days", defaultValue = "30") int days) {
        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);

        double[] history;
        if (devGuard) {
            log.info("[AiopsController] EP_DEV_ONLY active — synthesizing capacity history for {}", metric);
            history = capacityHistoryStore.computeIfAbsent(metric, k -> synthesizeCapacityHistory(k));
        } else {
            log.info("[AiopsController] Production mode — reading capacity history for {}", metric);
            history = loadProductionCapacityHistory(metric);
        }
        ForecastResult result = capacityForecaster.forecast(history, days);
        return ResponseEntity.ok(result);
    }

    /**
     * Root-cause analysis from a set of anomalous metrics.
     *
     * @param anomalousMetrics set of anomalous metric names
     * @return a {@link RootCauseReport}
     */
    @PostMapping("/root-cause")
    public ResponseEntity<RootCauseReport> rootCause(@RequestBody Set<String> anomalousMetrics) {
        RootCauseReport report = rootCauseAnalyzer.analyze(anomalousMetrics);
        return ResponseEntity.ok(report);
    }

    /**
     * Scheduling optimization suggestion for a single Flink job.
     *
     * @param profile Flink job profile
     * @return an {@link OptimizationSuggestion}
     */
    @PostMapping("/schedule/optimize")
    public ResponseEntity<OptimizationSuggestion> scheduleOptimize(@RequestBody FlinkJobProfile profile) {
        OptimizationSuggestion suggestion = scheduleOptimizer.optimize(profile);
        return ResponseEntity.ok(suggestion);
    }

    // ------------------------------------------------------------------
    // Dev/CI synthetic data generators
    // ------------------------------------------------------------------

    /**
     * Build a {@code [60][4]} feature matrix of normal ops metrics with one
     * injected outlier sample so the anomaly pipeline is exercisable in
     * dev/CI without Prometheus.
     */
    private static double[][] synthesizeAnomalyFeatures() {
        int n = 60;
        double[][] features = new double[n][4];
        Random r = new Random(0xA10L); // fixed seed → deterministic
        for (int i = 0; i < n; i++) {
            features[i][0] = 50.0 + r.nextGaussian() * 10.0;   // CDC latency ms
            features[i][1] = 200.0 + r.nextGaussian() * 30.0;  // Flink checkpoint ms
            features[i][2] = 2.0 + r.nextGaussian() * 0.5;     // compaction freq / hour
            features[i][3] = 800.0 + r.nextGaussian() * 100.0; // Trino P99 ms
        }
        // Inject an extreme outlier at index 30.
        features[30][0] = 5000.0;
        features[30][1] = 9000.0;
        features[30][2] = 20.0;
        features[30][3] = 15000.0;
        return features;
    }

    /**
     * Synthesize a 30-day upward-trending capacity history for a metric.
     */
    private static double[] synthesizeCapacityHistory(String metric) {
        int n = 30;
        double[] history = new double[n];
        Random r = new Random(metric.hashCode() ^ 0xCL);
        double base = "storage".equalsIgnoreCase(metric) ? 5_000_000_000.0 : 50.0;
        double growth = "storage".equalsIgnoreCase(metric) ? 200_000_000.0 : 1.5;
        for (int i = 0; i < n; i++) {
            history[i] = base + growth * i + r.nextGaussian() * (base * 0.01);
        }
        return history;
    }

    // ------------------------------------------------------------------
    // Production stubs (not implemented in MVP)
    // ------------------------------------------------------------------

    /**
     * Production hook: load the feature matrix from
     * {@code ops.metrics_history}. Not implemented in MVP — returns an empty
     * matrix, which the detector handles gracefully.
     */
    private static double[][] loadProductionFeatures() {
        return new double[0][];
    }

    /**
     * Production hook: load capacity history for a metric from the metrics
     * store. Not implemented in MVP — returns an empty array.
     */
    private static double[] loadProductionCapacityHistory(String metric) {
        return new double[0];
    }
}
