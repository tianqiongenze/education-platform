package com.ep.data.aiops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the ONNX enhancement path of {@link ScheduleOptimizer}.
 *
 * <p>Verifies that the optimizer falls back to the pure-Java rule tree
 * when no ONNX model is available, and that the ONNX path returns null
 * in dev mode.
 */
class ScheduleOptimizerOnnxTest {

    private static FlinkJobProfile profile(double backpressure, int parallelism, int tmCores,
                                           int tmMemoryMb, long inputRecords, long durationMs) {
        return new FlinkJobProfile("job-1", inputRecords, durationMs, parallelism, tmCores, tmMemoryMb, backpressure);
    }

    @Test
    void null_model_manager_uses_rules_fallback() {
        ScheduleOptimizer optimizer = new ScheduleOptimizer();
        FlinkJobProfile p = profile(0.8, 4, 2, 4096, 1_000_000, 60_000);
        OptimizationSuggestion s = optimizer.optimize(p);
        assertNotNull(s);
        assertTrue(s.suggestedConfig().contains("parallelism=6"),
                "rules fallback should suggest parallelism=6, got: " + s.suggestedConfig());
    }

    @Test
    void explicit_null_provider_uses_rules_fallback() {
        ScheduleOptimizer optimizer = new ScheduleOptimizer(null);
        FlinkJobProfile p = profile(0.05, 8, 4, 4096, 1_000_000, 10_000);
        OptimizationSuggestion s = optimizer.optimize(p);
        assertNotNull(s);
        assertTrue(s.suggestedConfig().contains("parallelism=6"),
                "rules fallback should fire, got: " + s.suggestedConfig());
    }

    @Test
    void unavailable_model_falls_back_to_rules() {
        ModelManager manager = new ModelManager(true, "/nonexistent");
        ObjectProvider<ModelManager> provider = new SingletonObjectProvider<>(manager);
        ScheduleOptimizer optimizer = new ScheduleOptimizer(provider);

        FlinkJobProfile p = profile(0.8, 4, 2, 4096, 1_000_000, 60_000);
        OptimizationSuggestion s = optimizer.optimize(p);
        assertNotNull(s);
        assertTrue(s.suggestedConfig().contains("parallelism=6"),
                "should fall back to rules when model unavailable, got: " + s.suggestedConfig());
        assertEquals(-10.0, s.estimatedCostSavingPct(), 1e-9);
    }

    @Test
    void optimize_with_gbt_returns_null_in_dev_mode() {
        ScheduleOptimizer optimizer = new ScheduleOptimizer();
        FlinkJobProfile p = profile(0.3, 4, 2, 4096, 1_000_000, 100_000);
        String current = "parallelism=4, tmCores=2, tmMemory=4096MB";
        // Direct call to package-private ONNX path — should return null.
        OptimizationSuggestion gbtResult = optimizer.optimizeWithGbt(p, current);
        assertNull(gbtResult, "GBT path should return null when no model manager is available");
    }

    @Test
    void optimize_with_gbt_returns_null_when_model_unavailable() {
        ModelManager manager = new ModelManager(true, "/nonexistent");
        ObjectProvider<ModelManager> provider = new SingletonObjectProvider<>(manager);
        ScheduleOptimizer optimizer = new ScheduleOptimizer(provider);

        FlinkJobProfile p = profile(0.3, 4, 2, 4096, 1_000_000, 100_000);
        String current = "parallelism=4, tmCores=2, tmMemory=4096MB";
        OptimizationSuggestion gbtResult = optimizer.optimizeWithGbt(p, current);
        assertNull(gbtResult, "GBT path should return null when model is in dev-only mode");
    }

    @Test
    void optimize_with_rules_works_directly() {
        ScheduleOptimizer optimizer = new ScheduleOptimizer();
        FlinkJobProfile p = profile(0.8, 4, 2, 4096, 1_000_000, 60_000);
        String current = "parallelism=4, tmCores=2, tmMemory=4096MB";
        OptimizationSuggestion s = optimizer.optimizeWithRules(p, current);
        assertNotNull(s);
        assertTrue(s.suggestedConfig().contains("parallelism=6"),
                "rules should suggest parallelism=6, got: " + s.suggestedConfig());
    }

    @Test
    void all_rule_paths_exercised_with_provider() {
        ModelManager manager = new ModelManager(true, "/nonexistent");
        ObjectProvider<ModelManager> provider = new SingletonObjectProvider<>(manager);
        ScheduleOptimizer optimizer = new ScheduleOptimizer(provider);

        // Rule 1: high backpressure.
        FlinkJobProfile p1 = profile(0.8, 4, 2, 4096, 1_000_000, 60_000);
        OptimizationSuggestion s1 = optimizer.optimize(p1);
        assertTrue(s1.reason().contains("Backpressure"));

        // Rule 2: high per-record latency + low memory.
        FlinkJobProfile p2 = profile(0.3, 4, 2, 2048, 100_000, 200_000);
        OptimizationSuggestion s2 = optimizer.optimize(p2);
        assertTrue(s2.suggestedConfig().contains("tmMemory=8192MB"));

        // Rule 3: low backpressure + high parallelism.
        FlinkJobProfile p3 = profile(0.05, 8, 4, 4096, 1_000_000, 10_000);
        OptimizationSuggestion s3 = optimizer.optimize(p3);
        assertTrue(s3.suggestedConfig().contains("parallelism=6"));

        // Rule 4: low backpressure + high cores.
        FlinkJobProfile p4 = profile(0.05, 4, 8, 4096, 1_000_000, 10_000);
        OptimizationSuggestion s4 = optimizer.optimize(p4);
        assertTrue(s4.suggestedConfig().contains("tmCores=4"));

        // Rule 5: optimal.
        FlinkJobProfile p5 = profile(0.3, 4, 2, 4096, 1_000_000, 100_000);
        OptimizationSuggestion s5 = optimizer.optimize(p5);
        assertTrue(s5.reason().contains("Optimal"));
    }

    @Test
    void null_profile_returns_no_profile_suggestion() {
        ScheduleOptimizer optimizer = new ScheduleOptimizer();
        OptimizationSuggestion s = optimizer.optimize(null);
        assertNotNull(s);
        assertTrue(s.reason().contains("No profile"),
                "null profile should produce a 'no profile' suggestion, got: " + s.reason());
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
