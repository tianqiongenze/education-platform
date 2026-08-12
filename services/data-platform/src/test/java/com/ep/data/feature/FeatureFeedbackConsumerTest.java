package com.ep.data.feature;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link FeatureFeedbackConsumer}.
 */
class FeatureFeedbackConsumerTest {

    private final FeatureFeedbackConsumer consumer = new FeatureFeedbackConsumer();

    @Test
    void test_json_parsed_correctly() {
        String json = "{\"model_id\":\"m1\",\"feature_importance\":["
                + "{\"name\":\"f1\",\"shap\":0.5},"
                + "{\"name\":\"f2\",\"shap\":0.3}]}";
        FeatureImportanceUpdate update = consumer.consume(json);
        assertEquals("m1", update.modelId(), "modelId should be m1, got " + update.modelId());
        assertEquals(2, update.rankings().size(), "should have 2 rankings, got " + update.rankings().size());
    }

    @Test
    void test_bottom_20_percent_identified() {
        StringBuilder sb = new StringBuilder("{\"model_id\":\"m1\",\"feature_importance\":[");
        for (int i = 1; i <= 10; i++) {
            if (i > 1) sb.append(',');
            sb.append("{\"name\":\"f").append(i).append("\",\"shap\":").append(1.0 - i * 0.05).append('}');
        }
        sb.append("]}");
        FeatureImportanceUpdate update = consumer.consume(sb.toString());
        assertEquals(2, update.bottomFeatures().size(),
                "10 features → bottom 20% = 2, got " + update.bottomFeatures());
    }

    @Test
    void test_empty_json_returns_empty() {
        FeatureImportanceUpdate update = consumer.consume("");
        assertTrue(update.rankings().isEmpty(), "empty JSON → empty rankings");
        assertTrue(update.bottomFeatures().isEmpty(), "empty JSON → empty bottomFeatures");
    }

    @Test
    void test_single_feature() {
        String json = "{\"model_id\":\"m1\",\"feature_importance\":[{\"name\":\"f1\",\"shap\":0.5}]}";
        FeatureImportanceUpdate update = consumer.consume(json);
        assertEquals(1, update.rankings().size());
        assertEquals(1, update.bottomFeatures().size(),
                "single feature → bottom min 1, got " + update.bottomFeatures().size());
        assertTrue(update.bottomFeatures().contains("f1"));
    }

    @Test
    void test_invalid_json_returns_empty() {
        FeatureImportanceUpdate update = consumer.consume("not json");
        assertTrue(update.rankings().isEmpty(), "invalid JSON → empty rankings");
        assertTrue(update.bottomFeatures().isEmpty(), "invalid JSON → empty bottomFeatures");
    }

    @Test
    void test_ranking_assigned_correctly() {
        String json = "{\"model_id\":\"m1\",\"feature_importance\":["
                + "{\"name\":\"low\",\"shap\":0.1},"
                + "{\"name\":\"high\",\"shap\":0.9},"
                + "{\"name\":\"mid\",\"shap\":0.5}]}";
        FeatureImportanceUpdate update = consumer.consume(json);
        List<FeatureImportance> rankings = update.rankings();
        assertEquals(1, rankings.get(0).rank(), "rank 1 should be highest SHAP, got " + rankings.get(0).rank());
        assertEquals("high", rankings.get(0).featureName());
        assertEquals(2, rankings.get(1).rank());
        assertEquals(3, rankings.get(2).rank());
        assertEquals("low", rankings.get(2).featureName());
    }

    @Test
    void test_null_input_returns_empty() {
        FeatureImportanceUpdate update = consumer.consume(null);
        assertNotNull(update);
        assertTrue(update.rankings().isEmpty());
    }
}
