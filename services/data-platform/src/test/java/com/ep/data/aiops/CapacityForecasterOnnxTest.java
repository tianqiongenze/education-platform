package com.ep.data.aiops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the ONNX enhancement path of {@link CapacityForecaster}.
 *
 * <p>These tests verify the two-path architecture:
 * <ul>
 *   <li>When no {@link ModelManager} is available (dev/CI), the forecaster
 *       uses the pure-Java EWMA + linear regression fallback.</li>
 *   <li>When a {@link ModelManager} is provided but the ONNX model is
 *       unavailable, the forecaster still falls back to pure-Java.</li>
 * </ul>
 */
class CapacityForecasterOnnxTest {

    @Test
    void null_model_manager_uses_ewma_fallback() {
        CapacityForecaster forecaster = new CapacityForecaster();
        double[] history = {10, 20, 30, 40, 50};
        ForecastResult fr = forecaster.forecast(history, 3);
        assertNotNull(fr);
        assertEquals(3, fr.predictedValues().length);
        // EWMA path should produce a positive slope for an upward trend.
        assertTrue(fr.slope() > 0.0,
                "EWMA fallback should produce positive slope for upward trend, got " + fr.slope());
    }

    @Test
    void explicit_null_provider_uses_ewma_fallback() {
        CapacityForecaster forecaster = new CapacityForecaster(null);
        double[] history = {10, 20, 30, 40, 50};
        ForecastResult fr = forecaster.forecast(history, 2);
        assertNotNull(fr);
        assertEquals(2, fr.predictedValues().length);
    }

    @Test
    void unavailable_model_falls_back_to_ewma() {
        // ModelManager in dev-only mode → isAvailable returns false → fallback.
        ModelManager manager = new ModelManager(true, "/nonexistent");
        ObjectProvider<ModelManager> provider = new SingletonObjectProvider<>(manager);
        CapacityForecaster forecaster = new CapacityForecaster(provider);

        double[] history = {10, 20, 30, 40, 50};
        ForecastResult fr = forecaster.forecast(history, 3);
        assertNotNull(fr);
        assertEquals(3, fr.predictedValues().length);
        // Should have used EWMA (non-zero slope for upward trend).
        assertTrue(fr.slope() > 0.0,
                "should fall back to EWMA when model unavailable, slope=" + fr.slope());
    }

    @Test
    void forecast_with_lstm_returns_null_in_dev_mode() {
        CapacityForecaster forecaster = new CapacityForecaster();
        double[] history = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        // Direct call to package-private method — should return null (no model).
        ForecastResult lstmResult = forecaster.forecastWithLstm(history, 3);
        assertNull(lstmResult, "LSTM path should return null when no model manager is available");
    }

    @Test
    void forecast_with_lstm_returns_null_when_model_unavailable() {
        ModelManager manager = new ModelManager(true, "/nonexistent");
        ObjectProvider<ModelManager> provider = new SingletonObjectProvider<>(manager);
        CapacityForecaster forecaster = new CapacityForecaster(provider);

        double[] history = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        ForecastResult lstmResult = forecaster.forecastWithLstm(history, 3);
        assertNull(lstmResult, "LSTM path should return null when model is in dev-only mode");
    }

    @Test
    void forecast_with_ewma_works_directly() {
        CapacityForecaster forecaster = new CapacityForecaster();
        double[] history = {10, 20, 30, 40, 50};
        ForecastResult fr = forecaster.forecastWithEwma(history, 3);
        assertNotNull(fr);
        assertEquals(3, fr.predictedValues().length);
        assertTrue(fr.slope() > 0.0);
    }

    @Test
    void empty_history_returns_empty_regardless_of_model_manager() {
        ModelManager manager = new ModelManager(true, "/nonexistent");
        ObjectProvider<ModelManager> provider = new SingletonObjectProvider<>(manager);
        CapacityForecaster forecaster = new CapacityForecaster(provider);

        ForecastResult fr = forecaster.forecast(new double[0], 5);
        assertEquals(0, fr.predictedValues().length);
    }

    // ─── Test helpers ──────────────────────────────────────────────────

    /**
     * Minimal {@link ObjectProvider} that always returns a fixed instance.
     */
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
