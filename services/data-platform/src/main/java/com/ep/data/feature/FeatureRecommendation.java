package com.ep.data.feature;

/**
 * A single auto-recommended feature candidate (§12.2.1).
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code "SHAP"} — derived from model SHAP importance ranking.</li>
 *   <li>{@code "COLLAB"} — collaborative filtering from similar models.</li>
 *   <li>{@code "IV"} — statistical Information Value (§12.2.1).</li>
 * </ul>
 *
 * @param featureName recommended feature name
 * @param source      one of "SHAP", "COLLAB", "IV"
 * @param score       confidence score in [0,1]
 * @param reason      human-readable explanation
 */
public record FeatureRecommendation(
        String featureName,
        String source,
        double score,
        String reason
) {
}
