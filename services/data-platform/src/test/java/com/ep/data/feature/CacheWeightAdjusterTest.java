package com.ep.data.feature;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link CacheWeightAdjuster}.
 */
class CacheWeightAdjusterTest {

    private final CacheWeightAdjuster adjuster = new CacheWeightAdjuster();

    private static FeatureImportanceUpdate update(int n) {
        List<FeatureImportance> rankings = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            // Higher index → lower SHAP, so feature "f1" is most important.
            rankings.add(new FeatureImportance("f" + i, n - i, i, "m1", LocalDateTime.now()));
        }
        return new FeatureImportanceUpdate("m1", rankings, Set.of());
    }

    @Test
    void test_top_20_percent_promoted() {
        CacheWeightReport report = adjuster.adjust(update(10));
        assertEquals(2, report.promotedFeatures().size(),
                "10 features → top 20% = 2 promoted, got " + report.promotedFeatures());
        assertTrue(report.promotedFeatures().contains("f1"), "top feature should be promoted");
        assertTrue(report.promotedFeatures().contains("f2"), "second feature should be promoted");
    }

    @Test
    void test_bottom_20_percent_demoted() {
        CacheWeightReport report = adjuster.adjust(update(10));
        assertEquals(2, report.demotedFeatures().size(),
                "10 features → bottom 20% = 2 demoted, got " + report.demotedFeatures());
        assertTrue(report.demotedFeatures().contains("f10"), "lowest feature should be demoted");
        assertTrue(report.demotedFeatures().contains("f9"), "second-lowest feature should be demoted");
    }

    @Test
    void test_middle_60_percent_unchanged() {
        CacheWeightReport report = adjuster.adjust(update(10));
        assertEquals(6, report.unchangedCount(),
                "10 features → middle 60% = 6 unchanged, got " + report.unchangedCount());
    }

    @Test
    void test_empty_update_returns_empty_report() {
        CacheWeightReport report = adjuster.adjust(null);
        assertTrue(report.promotedFeatures().isEmpty());
        assertTrue(report.demotedFeatures().isEmpty());
        assertEquals(0, report.unchangedCount());

        CacheWeightReport report2 = adjuster.adjust(
                new FeatureImportanceUpdate("m1", List.of(), Set.of()));
        assertTrue(report2.promotedFeatures().isEmpty());
        assertEquals(0, report2.unchangedCount());
    }
}
