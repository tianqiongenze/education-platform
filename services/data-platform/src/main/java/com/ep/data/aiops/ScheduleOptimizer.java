package com.ep.data.aiops;

import java.nio.FloatBuffer;
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
 * Flink job scheduler optimizer driven by a hand-written decision tree
 * (§12.3.3), with an optional ONNX Gradient-Boosted Trees enhancement
 * path (D-189).
 *
 * <p><b>Two inference paths:</b>
 * <ul>
 *   <li><b>ONNX GBT (D-189):</b> When an ONNX model is available via
 *       {@link ModelManager}, the job profile features are fed to a
 *       pre-trained GBT model that predicts the optimal parallelism, cores,
 *       and memory. The GBT captures non-linear interactions that the rule
 *       tree cannot.</li>
 *   <li><b>Pure-Java rules (fallback):</b> The original hand-written
 *       decision tree. This is the default in dev/CI and is always
 *       available.</li>
 * </ul>
 *
 * <p>Rules (evaluated in priority order, pure-Java fallback):
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

    private static final Logger log = LoggerFactory.getLogger(ScheduleOptimizer.class);

    /** Number of features fed to the GBT model. */
    static final int GBT_FEATURE_DIM = 6;

    private final ObjectProvider<ModelManager> modelManagerProvider;

    /** Default constructor — pure-Java rules (dev/CI mode). */
    public ScheduleOptimizer() {
        this(null);
    }

    /**
     * Create an optimizer with a model manager provider (D-189 ONNX path).
     *
     * @param modelManagerProvider Spring {@link ObjectProvider} for
     *        {@link ModelManager}; may be null in tests
     */
    public ScheduleOptimizer(ObjectProvider<ModelManager> modelManagerProvider) {
        this.modelManagerProvider = modelManagerProvider;
    }

    /**
     * Produce an optimization suggestion for a single Flink job profile.
     *
     * <p>Attempts the ONNX GBT path first; falls back to the pure-Java
     * rule tree when the model is unavailable.
     *
     * @param profile job profile (must not be null)
     * @return an {@link OptimizationSuggestion}
     */
    public OptimizationSuggestion optimize(FlinkJobProfile profile) {
        String current = describe(profile);
        if (profile == null) {
            return new OptimizationSuggestion("unknown", "n/a", "n/a", 0.0, "No profile provided");
        }

        // Try ONNX GBT path.
        OptimizationSuggestion gbtResult = optimizeWithGbt(profile, current);
        if (gbtResult != null) {
            return gbtResult;
        }

        // Fall back to pure-Java rules.
        return optimizeWithRules(profile, current);
    }

    /**
     * ONNX GBT inference path (D-189).
     *
     * <p>Packs the profile features into a {@code [1][6]} float tensor
     * (backpressure, parallelism, tmCores, tmMemoryMb, inputRecords/1M,
     * durationMs/1000), runs the GBT model, and maps the output to an
     * {@link OptimizationSuggestion}. Returns {@code null} when the model
     * is unavailable or inference fails.
     *
     * @param profile job profile
     * @param current current config string
     * @return suggestion, or {@code null} to fall back
     */
    OptimizationSuggestion optimizeWithGbt(FlinkJobProfile profile, String current) {
        ModelManager manager = resolveModelManager();
        if (manager == null || !manager.isAvailable(ModelManager.MODEL_SCHEDULE_GBT)) {
            return null;
        }
        OrtSession session = manager.getSession(ModelManager.MODEL_SCHEDULE_GBT);
        if (session == null) {
            return null;
        }
        try {
            float[] features = prepareGbtFeatures(profile);
            long[] shape = {1, GBT_FEATURE_DIM};
            FloatBuffer buf = FloatBuffer.wrap(features);
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, buf, shape)) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                String inputName = session.getInputNames().iterator().next();
                inputs.put(inputName, inputTensor);
                try (OrtSession.Result result = session.run(inputs)) {
                    return extractGbtOutput(result, profile, current);
                }
            }
        } catch (OrtException e) {
            log.warn("[ScheduleOptimizer] ONNX GBT inference failed, falling back to rules: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[ScheduleOptimizer] ONNX GBT path error, falling back to rules: {}", e.getMessage());
            return null;
        }
    }

    /** Prepare the 6-feature vector for the GBT model. */
    private float[] prepareGbtFeatures(FlinkJobProfile profile) {
        float[] f = new float[GBT_FEATURE_DIM];
        f[0] = (float) profile.backpressureRatio();
        f[1] = profile.parallelism();
        f[2] = profile.tmCores();
        f[3] = profile.tmMemoryMb();
        f[4] = (float) (profile.inputRecords() / 1_000_000.0);
        f[5] = (float) (profile.durationMs() / 1000.0);
        return f;
    }

    /** Extract GBT output and build an OptimizationSuggestion. */
    private OptimizationSuggestion extractGbtOutput(OrtSession.Result result,
                                                     FlinkJobProfile profile,
                                                     String current) throws OrtException {
        Object value = result.get(0).getValue();
        // GBT may output: [newParallelism, newTmCores, newTmMemoryMb, costSavingPct]
        // or a classification label. We handle the float[] case.
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
        if (preds == null || preds.length < 3) {
            return null;
        }
        int newParallelism = Math.max(1, (int) preds[0]);
        int newTmCores = Math.max(1, (int) preds[1]);
        int newTmMemoryMb = Math.max(1024, (int) preds[2]);
        double costSaving = preds.length > 3 ? preds[3] : 0.0;
        String suggested = describe(newParallelism, newTmCores, newTmMemoryMb);
        return new OptimizationSuggestion(
                profile.jobId(),
                current,
                suggested,
                costSaving,
                "GBT model suggested parallelism=" + newParallelism
                        + ", tmCores=" + newTmCores + ", tmMemory=" + newTmMemoryMb + "MB");
    }

    /**
     * Pure-Java rule-based optimization (the original fallback).
     */
    OptimizationSuggestion optimizeWithRules(FlinkJobProfile profile, String current) {
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

    /** Resolve the ModelManager from the ObjectProvider (null if unavailable). */
    private ModelManager resolveModelManager() {
        if (modelManagerProvider == null) {
            return null;
        }
        return modelManagerProvider.getIfAvailable();
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
