package com.ep.data.aiops;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * D-189: Centralized ONNX Runtime model manager.
 *
 * <p>Loads and caches {@link OrtSession} instances for the three AIOps models:
 * <ul>
 *   <li>{@code capacity-lstm} — LSTM for capacity forecasting.</li>
 *   <li>{@code anomaly-if} — Isolation Forest for anomaly detection.</li>
 *   <li>{@code schedule-gbt} — Gradient-Boosted Trees for schedule optimization.</li>
 * </ul>
 *
 * <p><b>Iron Law 9 (Real implementation, no fake model files):</b> This
 * manager does <em>not</em> ship with {@code .onnx} files. Models are loaded
 * from {@code ep.aiops.models.dir} (default {@code /opt/ep/models/aiops}) at
 * runtime. When the directory is absent or a model file is missing, the
 * manager returns {@code null} from {@link #getSession(String)} — callers
 * (CapacityForecaster, AnomalyDetector, ScheduleOptimizer) fall back to their
 * pure-Java implementations. This is the same pattern used by
 * {@code GnnLineageInferrer} for {@code model.json}.
 *
 * <p><b>EP_DEV_ONLY guard:</b> When {@code EP_DEV_ONLY} is unset, blank, or
 * {@code "true"}, the manager skips loading entirely and logs that the
 * pure-Java fallback path is active. Production deployments set
 * {@code EP_DEV_ONLY=false} and place trained {@code .onnx} files in the
 * models directory.
 *
 * <p><b>Thread safety:</b> Sessions are cached in a
 * {@link ConcurrentHashMap} (D-039). {@link OrtEnvironment} is a singleton
 * obtained via {@link OrtEnvironment#getEnvironment()} which is itself
 * thread-safe.
 */
@Component
public class ModelManager {

    private static final Logger log = LoggerFactory.getLogger(ModelManager.class);

    /** Known model names and their expected file names. */
    public static final String MODEL_CAPACITY_LSTM = "capacity-lstm";
    public static final String MODEL_ANOMALY_IF = "anomaly-if";
    public static final String MODEL_SCHEDULE_GBT = "schedule-gbt";

    private static final Map<String, String> MODEL_FILES = Map.of(
            MODEL_CAPACITY_LSTM, "capacity_lstm.onnx",
            MODEL_ANOMALY_IF, "anomaly_if.onnx",
            MODEL_SCHEDULE_GBT, "schedule_gbt.onnx"
    );

    /** Default models directory. Overridable via {@code ep.aiops.models.dir}. */
    static final String DEFAULT_MODELS_DIR = "/opt/ep/models/aiops";

    private final OrtEnvironment env;
    private final Map<String, OrtSession> sessions = new ConcurrentHashMap<>();
    private final boolean devOnly;
    private final String modelsDir;
    private volatile boolean initialized = false;

    /** Default constructor — reads env vars and system properties. */
    @org.springframework.beans.factory.annotation.Autowired
    public ModelManager() {
        this.env = createEnvironment();
        this.devOnly = computeDevOnly();
        this.modelsDir = resolveModelsDir();
    }

    /**
     * Test constructor with explicit parameters.
     *
     * @param devOnly   whether to skip ONNX loading
     * @param modelsDir directory containing {@code .onnx} files
     */
    public ModelManager(boolean devOnly, String modelsDir) {
        this.env = createEnvironment();
        this.devOnly = devOnly;
        this.modelsDir = modelsDir != null ? modelsDir : DEFAULT_MODELS_DIR;
    }

    /**
     * Get (or load) an ONNX session for the given model name.
     *
     * @param modelName one of {@link #MODEL_CAPACITY_LSTM},
     *                  {@link #MODEL_ANOMALY_IF}, {@link #MODEL_SCHEDULE_GBT}
     * @return the cached {@link OrtSession}, or {@code null} when:
     *         <ul>
     *           <li>{@code EP_DEV_ONLY} is active (dev mode)</li>
     *           <li>The model file does not exist</li>
     *           <li>ONNX Runtime fails to load the model</li>
     *         </ul>
     *         Callers must check for {@code null} and use their pure-Java
     *         fallback when it is returned.
     */
    public OrtSession getSession(String modelName) {
        if (devOnly) {
            return null;
        }
        if (!MODEL_FILES.containsKey(modelName)) {
            log.warn("[ModelManager] Unknown model name '{}'", modelName);
            return null;
        }
        // Lazy init — load all models on first access.
        if (!initialized) {
            initialize();
        }
        return sessions.get(modelName);
    }

    /**
     * Whether a valid ONNX session is available for the given model.
     *
     * @param modelName the model name
     * @return {@code true} if a loaded session is available
     */
    public boolean isAvailable(String modelName) {
        return getSession(modelName) != null;
    }

    /** Whether EP_DEV_ONLY is active (ONNX loading skipped). */
    public boolean isDevOnly() {
        return devOnly;
    }

    /** Get the models directory path. */
    public String getModelsDir() {
        return modelsDir;
    }

    /** Get the list of known model names. */
    public Set<String> getModelNames() {
        return Set.copyOf(MODEL_FILES.keySet());
    }

    // ─── Initialization ────────────────────────────────────────────────

    private synchronized void initialize() {
        if (initialized) {
            return;
        }
        if (env == null) {
            log.warn("[ModelManager] OrtEnvironment unavailable — ONNX path disabled.");
            initialized = true;
            return;
        }
        Path dir = Paths.get(modelsDir);
        if (!Files.isDirectory(dir)) {
            log.info("[ModelManager] Models directory '{}' does not exist — "
                    + "ONNX models will not be loaded (pure-Java fallback active).", modelsDir);
            initialized = true;
            return;
        }
        for (Map.Entry<String, String> entry : MODEL_FILES.entrySet()) {
            String modelName = entry.getKey();
            String fileName = entry.getValue();
            Path modelPath = dir.resolve(fileName);
            if (!Files.isRegularFile(modelPath)) {
                log.info("[ModelManager] Model file '{}' not found — '{}' uses pure-Java fallback.",
                        modelPath, modelName);
                continue;
            }
            try {
                byte[] modelBytes = Files.readAllBytes(modelPath);
                OrtSession session = env.createSession(modelBytes);
                sessions.put(modelName, session);
                log.info("[ModelManager] Loaded ONNX model '{}' from {}", modelName, modelPath);
            } catch (IOException e) {
                log.warn("[ModelManager] Failed to read model file '{}': {}", modelPath, e.getMessage());
            } catch (OrtException e) {
                log.warn("[ModelManager] ONNX Runtime failed to load model '{}': {}", modelName, e.getMessage());
            }
        }
        initialized = true;
    }

    // ─── Environment helpers ───────────────────────────────────────────

    private static OrtEnvironment createEnvironment() {
        try {
            return OrtEnvironment.getEnvironment();
        } catch (UnsatisfiedLinkError | Exception e) {
            // ONNX Runtime native library not available (e.g. on some CI
            // runners or Alpine without glibc). Fall back gracefully.
            log.warn("[ModelManager] ONNX Runtime native library unavailable: {}. "
                    + "Pure-Java fallback will be used for all AIOps models.", e.getMessage());
            return null;
        }
    }

    private static boolean computeDevOnly() {
        String dev = System.getenv("EP_DEV_ONLY");
        if (dev == null || dev.isBlank()) {
            return true;
        }
        return !"false".equalsIgnoreCase(dev);
    }

    private static String resolveModelsDir() {
        String dir = System.getProperty("ep.aiops.models.dir");
        if (dir == null || dir.isBlank()) {
            dir = System.getenv("EP_AIOPS_MODELS_DIR");
        }
        return (dir != null && !dir.isBlank()) ? dir : DEFAULT_MODELS_DIR;
    }

    /**
     * Close all sessions and the environment. Called by Spring
     * {@code @PreDestroy} in production; safe to call multiple times.
     */
    @PreDestroy
    public void shutdown() {
        for (Map.Entry<String, OrtSession> entry : sessions.entrySet()) {
            try {
                entry.getValue().close();
                log.info("[ModelManager] Closed session for '{}'", entry.getKey());
            } catch (OrtException e) {
                log.warn("[ModelManager] Error closing session '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        sessions.clear();
        if (env != null) {
            try {
                env.close();
            } catch (Exception e) {
                log.debug("[ModelManager] Error closing OrtEnvironment: {}", e.getMessage());
            }
        }
    }
}
