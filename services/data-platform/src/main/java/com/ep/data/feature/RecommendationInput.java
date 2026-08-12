package com.ep.data.feature;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inputs to {@link RecommendationEngine} (§12.2.1).
 *
 * @param importanceRanking      SHAP-derived importance ranking for the target model
 * @param candidateFieldIvValues IV values keyed by candidate field name
 * @param existingFeatures       features already present in the target group
 * @param similarModelFeatures   features used by similar models (collaborative signal)
 */
public record RecommendationInput(
        List<FeatureImportance> importanceRanking,
        Map<String, Double> candidateFieldIvValues,
        Set<String> existingFeatures,
        Set<String> similarModelFeatures
) {
}
