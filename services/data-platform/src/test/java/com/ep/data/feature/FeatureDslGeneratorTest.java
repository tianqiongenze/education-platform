package com.ep.data.feature;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link FeatureDslGenerator}.
 */
class FeatureDslGeneratorTest {

    private final FeatureDslGenerator generator = new FeatureDslGenerator();

    @Test
    void test_yaml_generated_correctly() {
        String yaml = generator.generate(List.of(
                new FeatureRecommendation("f1", "SHAP", 0.9, "top shap")));
        assertTrue(yaml.contains("feature_group: auto_recommended"), "YAML should contain feature_group, got:\n" + yaml);
        assertTrue(yaml.contains("owner: ai_recommender"), "YAML should contain owner, got:\n" + yaml);
        assertTrue(yaml.contains("features:"), "YAML should contain features section, got:\n" + yaml);
    }

    @Test
    void test_multiple_features() {
        String yaml = generator.generate(List.of(
                new FeatureRecommendation("f1", "SHAP", 0.9, "r1"),
                new FeatureRecommendation("f2", "IV", 0.5, "r2"),
                new FeatureRecommendation("f3", "COLLAB", 0.5, "r3")));
        long count = yaml.lines().filter(l -> l.trim().startsWith("- name:")).count();
        assertEquals(3, count, "should have 3 feature entries, got " + count);
    }

    @Test
    void test_empty_list_returns_empty_features_yaml() {
        String yaml = generator.generate(List.of());
        assertTrue(yaml.contains("features: []"), "empty list → features: [], got:\n" + yaml);
        assertFalse(yaml.contains("- name:"), "empty list should have no feature entries");
    }

    @Test
    void test_yaml_contains_recommended_by() {
        String yaml = generator.generate(List.of(
                new FeatureRecommendation("f1", "SHAP", 0.9, "r1")));
        assertTrue(yaml.contains("recommended_by: SHAP"), "YAML should contain recommended_by source, got:\n" + yaml);
        assertTrue(yaml.contains("confidence:"), "YAML should contain confidence, got:\n" + yaml);
    }
}
