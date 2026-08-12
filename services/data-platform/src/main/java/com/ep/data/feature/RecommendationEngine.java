package com.ep.data.feature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Recommends features for a target model by fusing three signals (§12.2.1):
 * <ol>
 *   <li><b>SHAP</b> — top-K features from the model's own SHAP importance
 *       ranking, score normalized to [0,1] by the maximum SHAP value.</li>
 *   <li><b>COLLAB</b> — collaborative filtering: features used by similar
 *       models but absent from the target group. Fixed confidence 0.5.</li>
 *   <li><b>IV</b> — candidate fields whose Information Value exceeds
 *       {@link IVCalculator#STRONG_PREDICTIVE_THRESHOLD} (0.3). Score =
 *       {@code min(iv, 1.0)}.</li>
 * </ol>
 *
 * <p>Recommendations from all sources are merged and deduplicated: when the
 * same feature name appears from multiple sources, the highest score is kept
 * and the reasons are concatenated. The final list is sorted by score
 * descending.
 */
@Component
public class RecommendationEngine {

    private static final int SHAP_TOP_K = 5;
    private static final double COLLAB_SCORE = 0.5;

    /**
     * Produce a merged, sorted list of feature recommendations.
     *
     * @param input recommendation inputs (null/empty → empty list)
     * @return recommendations sorted by score descending
     */
    public List<FeatureRecommendation> recommend(RecommendationInput input) {
        if (input == null) {
            return List.of();
        }

        Map<String, FeatureRecommendation> merged = new HashMap<>();

        recommendShap(input, merged);
        recommendCollab(input, merged);
        recommendIv(input, merged);

        if (merged.isEmpty()) {
            return List.of();
        }

        List<FeatureRecommendation> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparingDouble(FeatureRecommendation::score).reversed()
                .thenComparing(r -> r.featureName() == null ? "" : r.featureName()));
        return out;
    }

    private void recommendShap(RecommendationInput input, Map<String, FeatureRecommendation> merged) {
        List<FeatureImportance> ranking = input.importanceRanking();
        if (ranking == null || ranking.isEmpty()) {
            return;
        }

        List<FeatureImportance> sorted = new ArrayList<>(ranking);
        sorted.sort(FeatureImportance.BY_SHAP_DESC);

        double maxShap = sorted.stream()
                .mapToDouble(FeatureImportance::shapValue)
                .max()
                .orElse(0.0);

        int limit = Math.min(SHAP_TOP_K, sorted.size());
        for (int i = 0; i < limit; i++) {
            FeatureImportance fi = sorted.get(i);
            if (fi.featureName() == null) {
                continue;
            }
            double score = maxShap <= 0.0 ? 0.0 : fi.shapValue() / maxShap;
            String reason = "SHAP rank " + (i + 1) + " (shap=" + fi.shapValue() + ")";
            merge(merged, fi.featureName(), "SHAP", score, reason);
        }
    }

    private void recommendCollab(RecommendationInput input, Map<String, FeatureRecommendation> merged) {
        Set<String> similar = input.similarModelFeatures();
        Set<String> existing = input.existingFeatures();
        if (similar == null || similar.isEmpty()) {
            return;
        }
        for (String feature : similar) {
            if (feature == null) {
                continue;
            }
            if (existing != null && existing.contains(feature)) {
                continue;
            }
            merge(merged, feature, "COLLAB", COLLAB_SCORE, "Used by similar model");
        }
    }

    private void recommendIv(RecommendationInput input, Map<String, FeatureRecommendation> merged) {
        Map<String, Double> ivValues = input.candidateFieldIvValues();
        if (ivValues == null || ivValues.isEmpty()) {
            return;
        }
        // Preserve a stable iteration order via LinkedHashMap.
        Map<String, Double> ordered = new LinkedHashMap<>(ivValues);
        for (Map.Entry<String, Double> e : ordered.entrySet()) {
            String feature = e.getKey();
            if (feature == null) {
                continue;
            }
            Double iv = e.getValue();
            if (iv == null) {
                continue;
            }
            if (iv > IVCalculator.STRONG_PREDICTIVE_THRESHOLD) {
                double score = Math.min(iv / 1.0, 1.0);
                String reason = "IV=" + iv + " (strong predictive)";
                merge(merged, feature, "IV", score, reason);
            }
        }
    }

    /**
     * Merge a recommendation into the accumulator, keeping the highest score
     * and concatenating reasons when the same feature appears multiple times.
     */
    private static void merge(Map<String, FeatureRecommendation> merged,
                              String featureName, String source, double score, String reason) {
        FeatureRecommendation existing = merged.get(featureName);
        if (existing == null) {
            merged.put(featureName, new FeatureRecommendation(featureName, source, score, reason));
            return;
        }
        double bestScore = Math.max(existing.score(), score);
        String mergedReason = existing.reason() + " | " + reason;
        String mergedSource = existing.source().equals(source) ? source : existing.source() + "+" + source;
        merged.put(featureName, new FeatureRecommendation(featureName, mergedSource, bestScore, mergedReason));
    }
}
