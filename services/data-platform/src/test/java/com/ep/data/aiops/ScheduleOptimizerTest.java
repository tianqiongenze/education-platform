package com.ep.data.aiops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link ScheduleOptimizer}.
 */
class ScheduleOptimizerTest {

    private final ScheduleOptimizer optimizer = new ScheduleOptimizer();

    private static FlinkJobProfile profile(double backpressure, int parallelism, int tmCores, int tmMemoryMb,
                                           long inputRecords, long durationMs) {
        return new FlinkJobProfile("job-1", inputRecords, durationMs, parallelism, tmCores, tmMemoryMb, backpressure);
    }

    @Test
    void test_high_backpressure_suggests_increase_parallelism() {
        FlinkJobProfile p = profile(0.8, 4, 2, 4096, 1_000_000, 60_000);
        OptimizationSuggestion s = optimizer.optimize(p);
        assertNotNull(s);
        assertTrue(s.suggestedConfig().contains("parallelism=6"),
                "should suggest parallelism=6 (4*1.5), got: " + s.suggestedConfig());
        assertEquals(-10.0, s.estimatedCostSavingPct(), 1e-9,
                "high backpressure should estimate -10% cost change, got " + s.estimatedCostSavingPct());
        assertTrue(s.reason().contains("Backpressure"), "reason should mention backpressure, got: " + s.reason());
    }

    @Test
    void test_low_utilization_suggests_decrease_cores() {
        // low backpressure AND tmCores > 4 AND parallelism <= 4 (so rule 3 doesn't fire first)
        FlinkJobProfile p = profile(0.05, 4, 8, 4096, 1_000_000, 10_000);
        OptimizationSuggestion s = optimizer.optimize(p);
        assertNotNull(s);
        assertTrue(s.suggestedConfig().contains("tmCores=4"),
                "should suggest tmCores=4, got: " + s.suggestedConfig());
        assertEquals(20.0, s.estimatedCostSavingPct(), 1e-9,
                "core reduction should estimate 20% saving, got " + s.estimatedCostSavingPct());
    }

    @Test
    void test_normal_config_no_optimization() {
        // Balanced profile: moderate backpressure, modest parallelism, reasonable memory/cores.
        FlinkJobProfile p = profile(0.3, 4, 2, 4096, 1_000_000, 100_000);
        OptimizationSuggestion s = optimizer.optimize(p);
        assertNotNull(s);
        assertTrue(s.reason().contains("Optimal"),
                "balanced profile should be optimal, got reason: " + s.reason());
        assertEquals(0.0, s.estimatedCostSavingPct(), 1e-9,
                "optimal config should have 0% cost change, got " + s.estimatedCostSavingPct());
    }

    @Test
    void test_high_duration_suggests_more_memory() {
        // durationMs / inputRecords > 1.0 AND tmMemoryMb < 4096
        // 200_000 ms / 100_000 records = 2.0 ms/record
        FlinkJobProfile p = profile(0.3, 4, 2, 2048, 100_000, 200_000);
        OptimizationSuggestion s = optimizer.optimize(p);
        assertNotNull(s);
        assertTrue(s.suggestedConfig().contains("tmMemory=8192MB"),
                "should suggest tmMemory=8192MB, got: " + s.suggestedConfig());
        assertTrue(s.reason().contains("memory"),
                "reason should mention memory, got: " + s.reason());
    }

    @Test
    void test_low_backpressure_suggests_decrease_parallelism() {
        // backpressure < 0.1 AND parallelism > 4 (rule 3 fires before rule 4
        // because parallelism 8 > 4 even with tmCores 8)
        // To isolate rule 3, keep tmCores <= 4 so rule 4 wouldn't fire anyway.
        FlinkJobProfile p = profile(0.05, 8, 4, 4096, 1_000_000, 10_000);
        OptimizationSuggestion s = optimizer.optimize(p);
        assertNotNull(s);
        assertTrue(s.suggestedConfig().contains("parallelism=6"),
                "should suggest parallelism=6 (8*0.75), got: " + s.suggestedConfig());
        assertEquals(15.0, s.estimatedCostSavingPct(), 1e-9,
                "parallelism reduction should estimate 15% saving, got " + s.estimatedCostSavingPct());
    }
}
