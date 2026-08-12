package com.ep.data.feature;

import java.time.LocalDateTime;
import java.util.Comparator;

/**
 * Importance ranking of a single feature derived from SHAP feedback (§12.7).
 *
 * <p>SHAP (SHapley Additive exPlanations) values quantify the marginal
 * contribution of each feature to a model's predictions. Higher absolute SHAP
 * values indicate more important features. This record pairs the raw SHAP value
 * with a 1-based rank (1 = most important) and provenance metadata.
 *
 * @param featureName feature name
 * @param shapValue   SHAP importance value (higher = more important)
 * @param rank        1-based rank (1 = highest SHAP)
 * @param modelId     source model identifier
 * @param updatedAt   timestamp of the most recent SHAP update
 */
public record FeatureImportance(
        String featureName,
        double shapValue,
        int rank,
        String modelId,
        LocalDateTime updatedAt
) implements Comparable<FeatureImportance> {

    /**
     * Comparator that orders feature importances by SHAP value descending
     * (most important first). Ties break by feature name ascending for
     * deterministic ordering.
     */
    public static final Comparator<FeatureImportance> BY_SHAP_DESC =
            Comparator.comparingDouble(FeatureImportance::shapValue).reversed()
                    .thenComparing(fi -> fi.featureName() == null ? "" : fi.featureName());

    /**
     * Compare by SHAP value descending. A higher SHAP value is considered
     * "smaller" for sorting purposes so that natural ordering places the most
     * important features first.
     *
     * @param other the other feature importance
     * @return negative if this is more important, positive if less, 0 if equal
     */
    @Override
    public int compareTo(FeatureImportance other) {
        return BY_SHAP_DESC.compare(this, other);
    }
}
