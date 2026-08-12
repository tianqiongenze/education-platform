package com.ep.data.aiops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ModelManager} in dev-only mode (no ONNX files on disk).
 *
 * <p>These tests exercise the EP_DEV_ONLY guard and graceful-degradation
 * paths without requiring a real ONNX runtime or model files. The manager
 * should be a no-op that returns {@code null} sessions and reports
 * unavailable for all models.
 */
class ModelManagerTest {

    @Test
    void dev_only_mode_returns_null_sessions() {
        // Default constructor: EP_DEV_ONLY is typically unset in CI → devOnly=true.
        // Even if the native library is missing, the manager must not throw.
        ModelManager manager = new ModelManager(true, "/nonexistent/models/dir");
        assertNull(manager.getSession(ModelManager.MODEL_CAPACITY_LSTM),
                "dev-only mode should return null session");
        assertNull(manager.getSession(ModelManager.MODEL_ANOMALY_IF),
                "dev-only mode should return null session");
        assertNull(manager.getSession(ModelManager.MODEL_SCHEDULE_GBT),
                "dev-only mode should return null session");
    }

    @Test
    void dev_only_mode_isAvailable_false() {
        ModelManager manager = new ModelManager(true, "/nonexistent/models/dir");
        assertFalse(manager.isAvailable(ModelManager.MODEL_CAPACITY_LSTM),
                "dev-only mode should report unavailable");
        assertFalse(manager.isAvailable(ModelManager.MODEL_ANOMALY_IF),
                "dev-only mode should report unavailable");
        assertFalse(manager.isAvailable(ModelManager.MODEL_SCHEDULE_GBT),
                "dev-only mode should report unavailable");
    }

    @Test
    void dev_only_flag_reflected() {
        ModelManager devManager = new ModelManager(true, "/nonexistent");
        assertTrue(devManager.isDevOnly(), "devOnly=true should be reflected");
    }

    @Test
    void models_dir_returned() {
        ModelManager manager = new ModelManager(true, "/custom/models");
        assertTrue(manager.getModelsDir().equals("/custom/models"),
                "models dir should match constructor argument");
    }

    @Test
    void default_models_dir_when_null_provided() {
        ModelManager manager = new ModelManager(true, null);
        assertNotNull(manager.getModelsDir(), "models dir should not be null");
        assertTrue(manager.getModelsDir().equals(ModelManager.DEFAULT_MODELS_DIR),
                "null dir should fall back to DEFAULT_MODELS_DIR");
    }

    @Test
    void unknown_model_name_returns_null() {
        ModelManager manager = new ModelManager(false, "/nonexistent");
        assertNull(manager.getSession("unknown-model"),
                "unknown model name should return null");
    }

    @Test
    void model_names_contains_all_three() {
        ModelManager manager = new ModelManager(true, "/nonexistent");
        assertTrue(manager.getModelNames().contains(ModelManager.MODEL_CAPACITY_LSTM),
                "model names should include capacity-lstm");
        assertTrue(manager.getModelNames().contains(ModelManager.MODEL_ANOMALY_IF),
                "model names should include anomaly-if");
        assertTrue(manager.getModelNames().contains(ModelManager.MODEL_SCHEDULE_GBT),
                "model names should include schedule-gbt");
    }

    @Test
    void production_mode_missing_dir_returns_null_sessions() {
        // Even in production mode (devOnly=false), if the models directory
        // doesn't exist, sessions should be null (graceful degradation).
        ModelManager manager = new ModelManager(false, "/nonexistent/models/dir");
        assertNull(manager.getSession(ModelManager.MODEL_CAPACITY_LSTM),
                "missing models dir should return null session");
        assertFalse(manager.isAvailable(ModelManager.MODEL_ANOMALY_IF),
                "missing models dir should report unavailable");
    }

    @Test
    void shutdown_is_safe_to_call() {
        ModelManager manager = new ModelManager(true, "/nonexistent");
        // shutdown() should not throw even when no sessions were loaded.
        manager.shutdown();
        // Calling shutdown again should also be safe.
        manager.shutdown();
    }
}
