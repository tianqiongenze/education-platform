package com.ep.data.feature;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link RecommendationEngine}.
 */
class RecommendationEngineTest {

    private final RecommendationEngine engine = new RecommendationEngine();

    private static FeatureImportance fi(String name, double shap) {
        return new FeatureImportance(name, shap, 0, "m1", LocalDateTime.now());
    }

    @Test
    void test_shap_recommendation() {
        RecommendationInput input = new RecommendationInput(
                List.of(fi("f1", 0.8), fi("f2", 0.4)),
                Map.of(),
                Set.of(),
                Set.of());
        List<FeatureRecommendation> recs = engine.recommend(input);
        assertTrue(recs.stream().anyMatch(r -> "SHAP".equals(r.source())),
                "should produce SHAP recommendations, got " + recs);
        assertTrue(recs.stream().anyMatch(r -> "f1".equals(r.featureName())));
    }

    @Test
    void test_iv_recommendation() {
        RecommendationInput input = new RecommendationInput(
                List.of(),
                Map.of("candidate_a", 0.5, "candidate_b", 0.1),
                Set.of(),
                Set.of());
        List<FeatureRecommendation> recs = engine.recommend(input);
        assertTrue(recs.stream().anyMatch(r -> "IV".equals(r.source()) && "candidate_a".equals(r.featureName())),
                "IV>0.3 field should be recommended, got " + recs);
        assertTrue(recs.stream().noneMatch(r -> "candidate_b".equals(r.featureName())),
                "IV<0.3 field should NOT be recommended");
    }

    @Test
    void test_collab_recommendation() {
        RecommendationInput input = new RecommendationInput(
                List.of(),
                Map.of(),
                Set.of("existing_f"),
                Set.of("collab_f", "existing_f"));
        List<FeatureRecommendation> recs = engine.recommend(input);
        assertTrue(recs.stream().anyMatch(r -> "COLLAB".equals(r.source()) && "collab_f".equals(r.featureName())),
                "collab-only feature should be recommended, got " + recs);
        assertTrue(recs.stream().noneMatch(r -> "existing_f".equals(r.featureName())),
                "existing feature should not be collab-recommended");
    }

    @Test
    void test_multi_source_merge_dedup() {
        // "f1" comes from SHAP (score 1.0 since it's the max) and IV (0.6).
        RecommendationInput input = new RecommendationInput(
                List.of(fi("f1", 0.8)),
                Map.of("f1", 0.6),
                Set.of(),
                Set.of());
        List<FeatureRecommendation> recs = engine.recommend(input);
        assertEquals(1, recs.size(), "dedup should yield 1 entry, got " + recs.size());
        FeatureRecommendation r = recs.get(0);
        assertEquals("f1", r.featureName());
        assertEquals(1.0, r.score(), 1e-9, "highest score (1.0) should be kept, got " + r.score());
        assertTrue(r.reason().contains("|"), "reasons should be merged, got " + r.reason());
    }

    @Test
    void test_sorted_by_score_descending() {
        RecommendationInput input = new RecommendationInput(
                List.of(fi("shap_low", 0.2), fi("shap_high", 1.0)),
                Map.of("iv_high", 0.9),
                Set.of(),
                Set.of());
        List<FeatureRecommendation> recs = engine.recommend(input);
        for (int i = 1; i < recs.size(); i++) {
            assertTrue(recs.get(i - 1).score() >= recs.get(i).score(),
                    "scores should be descending, but " + recs.get(i - 1).score() + " < " + recs.get(i).score());
        }
    }

    @Test
    void test_empty_input_returns_empty() {
        assertTrue(engine.recommend(null).isEmpty(), "null input → empty list");
        assertTrue(engine.recommend(new RecommendationInput(List.of(), Map.of(), Set.of(), Set.of())).isEmpty(),
                "all-empty input → empty list");
    }
}
