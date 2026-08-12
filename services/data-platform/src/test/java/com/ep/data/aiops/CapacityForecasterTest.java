package com.ep.data.aiops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link CapacityForecaster}.
 */
class CapacityForecasterTest {

    private final CapacityForecaster forecaster = new CapacityForecaster();

    @Test
    void test_stable_trend_flat_forecast() {
        // Constant history → slope should be ~0 and forecast flat.
        double[] history = new double[]{100, 100, 100, 100, 100, 100, 100, 100, 100, 100};
        ForecastResult fr = forecaster.forecast(history, 5);
        assertEquals(5, fr.predictedValues().length, "expected 5 predicted values, got " + fr.predictedValues().length);
        assertTrue(Math.abs(fr.slope()) < 1e-9, "flat history slope should be ~0, got " + fr.slope());
        for (int i = 0; i < fr.predictedValues().length; i++) {
            assertEquals(100.0, fr.predictedValues()[i], 1e-6,
                    "flat forecast value at " + i + " should be 100, got " + fr.predictedValues()[i]);
        }
    }

    @Test
    void test_upward_trend_positive_slope() {
        double[] history = new double[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        ForecastResult fr = forecaster.forecast(history, 3);
        assertTrue(fr.slope() > 0.0, "increasing history should have positive slope, got " + fr.slope());
        // EWMA smoothing lags the trend, so the predicted value should still
        // be well above the first history value and trend upward across steps.
        assertTrue(fr.predictedValues()[0] > 50.0,
                "next predicted value should be well above the series midpoint, got " + fr.predictedValues()[0]);
        assertTrue(fr.predictedValues()[2] > fr.predictedValues()[0],
                "forecast should keep rising: predicted[2]=" + fr.predictedValues()[2]
                        + " should be > predicted[0]=" + fr.predictedValues()[0]);
    }

    @Test
    void test_downward_trend_negative_slope() {
        double[] history = new double[]{100, 90, 80, 70, 60, 50, 40, 30, 20, 10};
        ForecastResult fr = forecaster.forecast(history, 3);
        assertTrue(fr.slope() < 0.0, "decreasing history should have negative slope, got " + fr.slope());
        // EWMA smoothing lags the trend, so the predicted value should still
        // be well below the first history value and trend downward across steps.
        assertTrue(fr.predictedValues()[0] < 60.0,
                "next predicted value should be well below the series midpoint, got " + fr.predictedValues()[0]);
        assertTrue(fr.predictedValues()[2] < fr.predictedValues()[0],
                "forecast should keep falling: predicted[2]=" + fr.predictedValues()[2]
                        + " should be < predicted[0]=" + fr.predictedValues()[0]);
    }

    @Test
    void test_empty_history_returns_empty() {
        ForecastResult fr = forecaster.forecast(new double[0], 5);
        assertEquals(0, fr.predictedValues().length, "empty history → no predicted values");
        assertEquals(0, fr.confidenceLower().length, "empty history → no lower bounds");
        assertEquals(0, fr.confidenceUpper().length, "empty history → no upper bounds");
        ForecastResult frNull = forecaster.forecast(null, 5);
        assertEquals(0, frNull.predictedValues().length, "null history → no predicted values");
    }

    @Test
    void test_single_point_flat_forecast() {
        ForecastResult fr = forecaster.forecast(new double[]{42.0}, 4);
        assertEquals(4, fr.predictedValues().length, "single point should still forecast N values");
        for (int i = 0; i < 4; i++) {
            assertEquals(42.0, fr.predictedValues()[i], 1e-9,
                    "single-point forecast should be flat at 42, got " + fr.predictedValues()[i] + " at " + i);
        }
        assertEquals(0.0, fr.slope(), 1e-9, "single-point slope should be 0, got " + fr.slope());
    }

    @Test
    void test_confidence_interval_contains_prediction() {
        double[] history = new double[]{10, 15, 20, 25, 30, 35, 40, 45, 50};
        ForecastResult fr = forecaster.forecast(history, 5);
        for (int i = 0; i < fr.predictedValues().length; i++) {
            double p = fr.predictedValues()[i];
            double lo = fr.confidenceLower()[i];
            double hi = fr.confidenceUpper()[i];
            assertTrue(lo <= p,
                    "lower[" + i + "]=" + lo + " should be <= prediction " + p);
            assertTrue(p <= hi,
                    "upper[" + i + "]=" + hi + " should be >= prediction " + p);
            assertTrue(lo <= hi, "lower should be <= upper at " + i);
        }
    }

    @Test
    void test_capacity_alert_triggered() {
        // History trending toward the limit; prediction should exceed 80%.
        double[] history = new double[]{40, 45, 50, 55, 60, 65, 70, 75, 80, 85};
        CapacityAlert alert = forecaster.checkCapacity("storage", history, 5, 100.0);
        assertNotNull(alert, "alert should be returned when predicted > 80% of capacity, history=" + java.util.Arrays.toString(history));
        assertTrue(alert.thresholdPct() > 0.8,
                "thresholdPct should exceed 0.8, got " + alert.thresholdPct());
        assertEquals("storage", alert.metricName(), "metric name should match input");
        assertEquals("STORAGE", alert.alertType(), "storage metric should classify as STORAGE");
    }

    @Test
    void test_capacity_alert_not_triggered_when_safe() {
        // Flat low history well below limit → no alert.
        double[] history = new double[]{10, 10, 10, 10, 10, 10, 10, 10, 10, 10};
        CapacityAlert alert = forecaster.checkCapacity("storage", history, 5, 1000.0);
        assertNull(alert, "no alert expected when predicted stays below 80% of capacity");
    }
}
