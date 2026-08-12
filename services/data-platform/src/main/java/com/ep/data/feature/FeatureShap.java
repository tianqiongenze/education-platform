package com.ep.data.feature;

/**
 * Single feature-to-SHAP pair used in the feedback event JSON payload (§12.7).
 *
 * <p>Maps directly to the {@code feature_importance} array entries in the
 * SHAP feedback event: {@code {"name": "...", "shap": 0.5}}.
 *
 * @param name feature name
 * @param shap SHAP importance value
 */
public record FeatureShap(String name, double shap) {
}
