package com.ep.data.feature;

import java.util.List;

/**
 * Inbound SHAP feedback event emitted by the model serving layer (§12.7).
 *
 * <p>JSON shape (per SDD §12.7):
 * <pre>{@code
 * {
 *   "model_id": "m1",
 *   "feature_importance": [
 *     {"name": "f1", "shap": 0.5},
 *     {"name": "f2", "shap": 0.3}
 *   ]
 * }
 * }</pre>
 *
 * <p>Consumed by {@link FeatureFeedbackConsumer} to refresh the feature
 * importance ranking and drive cache weight adjustments (§12.2.3).
 *
 * @param modelId            source model identifier
 * @param featureImportance  list of feature SHAP pairs
 */
public record FeatureFeedbackEvent(
        String modelId,
        List<FeatureShap> featureImportance
) {
}
