package com.ep.data.aiops;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the ONNX enhancement path of {@link AnomalyDetector}.
 *
 * <p>Verifies that the detector falls back to the pure-Java Isolation
 * Forest when no ONNX model is available, and that the ONNX path returns
 * null in dev mode.
 */
class AnomalyDetectorOnnxTest {

    @Test
    void null_model_manager_uses_java_if_fallback() {
        AnomalyDetector detector = new AnomalyDetector();
        double[][] features = {
                {50, 200, 2, 800}, {52, 195, 2.1, 810}, {48, 205, 1.9, 790},
                {51, 198, 2.0, 805}, {49, 202, 2.0, 800}
        };
        List<AnomalyResult> results = detector.detect(features);
        assertNotNull(results);
        assertEquals(5, results.size(), "should produce one result per sample");
    }

    @Test
    void explicit_null_provider_uses_java_if_fallback() {
        AnomalyDetector detector = new AnomalyDetector(null);
        double[][] features = {
                {50, 200, 2, 800}, {52, 195, 2.1, 810}, {48, 205, 1.9, 790}
        };
        List<AnomalyResult> results = detector.detect(features);
        assertEquals(3, results.size());
    }

    @Test
    void unavailable_model_falls_back_to_java_if() {
        ModelManager manager = new ModelManager(true, "/nonexistent");
        ObjectProvider<ModelManager> provider = new SingletonObjectProvider<>(manager);
        AnomalyDetector detector = new AnomalyDetector(provider);

        double[][] features = {
                {50, 200, 2, 800}, {52, 195, 2.1, 810}, {48, 205, 1.9, 790},
                {51, 198, 2.0, 805}, {49, 202, 2.0, 800}
        };
        List<AnomalyResult> results = detector.detect(features);
        assertNotNull(results);
        assertEquals(5, results.size(), "should fall back to Java IF");
    }

    @Test
    void detect_with_onnx_returns_null_in_dev_mode() {
        AnomalyDetector detector = new AnomalyDetector();
        double[][] features = {
                {50, 200, 2, 800}, {52, 195, 2.1, 810}, {48, 205, 1.9, 790}
        };
        // Direct call to package-private ONNX path — should return null.
        List<AnomalyResult> onnxResult = detector.detectWithOnnx(features);
        assertNull(onnxResult, "ONNX path should return null when no model manager is available");
    }

    @Test
    void detect_with_onnx_returns_null_when_model_unavailable() {
        ModelManager manager = new ModelManager(true, "/nonexistent");
        ObjectProvider<ModelManager> provider = new SingletonObjectProvider<>(manager);
        AnomalyDetector detector = new AnomalyDetector(provider);

        double[][] features = {
                {50, 200, 2, 800}, {52, 195, 2.1, 810}, {48, 205, 1.9, 790}
        };
        List<AnomalyResult> onnxResult = detector.detectWithOnnx(features);
        assertNull(onnxResult, "ONNX path should return null when model is in dev-only mode");
    }

    @Test
    void detect_with_onnx_returns_null_for_null_input() {
        AnomalyDetector detector = new AnomalyDetector();
        assertNull(detector.detectWithOnnx(null), "null input should return null from ONNX path");
    }

    @Test
    void detect_with_onnx_returns_null_for_single_sample() {
        AnomalyDetector detector = new AnomalyDetector();
        double[][] single = {{1.0, 2.0, 3.0, 4.0}};
        // Single sample → ONNX path delegates to pure-Java (returns null).
        assertNull(detector.detectWithOnnx(single),
                "single sample should return null from ONNX path (delegate to Java IF)");
    }

    @Test
    void detect_with_java_if_works_directly() {
        AnomalyDetector detector = new AnomalyDetector();
        double[][] features = {
                {50, 200, 2, 800}, {52, 195, 2.1, 810}, {48, 205, 1.9, 790},
                {50000, 80000, 100, 90000}, {49, 202, 2.0, 800}
        };
        List<AnomalyResult> results = detector.detectWithJavaIf(features);
        assertNotNull(results);
        assertEquals(5, results.size());
        // The outlier at index 3 should have a high score.
        assertTrue(results.get(3).anomalyScore() > results.get(0).anomalyScore(),
                "outlier should have higher score than normal samples");
    }

    @Test
    void sigma_detection_unaffected_by_model_manager() {
        ModelManager manager = new ModelManager(true, "/nonexistent");
        ObjectProvider<ModelManager> provider = new SingletonObjectProvider<>(manager);
        AnomalyDetector detector = new AnomalyDetector(provider);

        double[] values = {100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 500};
        boolean[] flags = detector.detectSigma(values);
        assertTrue(flags[flags.length - 1],
                "3-sigma detection should work regardless of model manager availability");
    }

    // ─── Test helpers ──────────────────────────────────────────────────

    static final class SingletonObjectProvider<T> implements ObjectProvider<T> {
        private final T instance;

        SingletonObjectProvider(T instance) {
            this.instance = instance;
        }

        @Override
        public T getObject(Object... args) {
            return instance;
        }

        @Override
        public T getIfAvailable() {
            return instance;
        }

        @Override
        public T getIfUnique() {
            return instance;
        }
    }
}
