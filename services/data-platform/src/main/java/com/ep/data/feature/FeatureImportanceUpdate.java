package com.ep.data.feature;

import java.util.List;
import java.util.Set;

/**
 * Processed output of a SHAP feedback event (§12.7), ready to be applied to a
 * {@link FeatureGroup} and the cache-weighting layer (§12.2.3).
 *
 * <p>Derived from {@link FeatureFeedbackEvent} by assigning 1-based ranks and
 * isolating the bottom 20% of features (candidates for lazy-load demotion).
 *
 * @param modelId        source model identifier
 * @param rankings       feature importances sorted by SHAP descending with ranks assigned
 * @param bottomFeatures names of the bottom 20% features by SHAP value
 */
public record FeatureImportanceUpdate(
        String modelId,
        List<FeatureImportance> rankings,
        Set<String> bottomFeatures
) {
}
