package com.ep.data.feature;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Generates the auto-recommended feature-group DSL (YAML) from a list of
 * {@link FeatureRecommendation} (§12.2.1).
 *
 * <p>Output template:
 * <pre>{@code
 * feature_group: auto_recommended
 * owner: ai_recommender
 * features:
 *   - name: <featureName>
 *     type: double
 *     transform: auto
 *     aggregation: sum
 *     window: 30d
 *     recommended_by: <source>
 *     confidence: <score>
 * }</pre>
 */
@Component
public class FeatureDslGenerator {

    private static final String HEADER =
            "feature_group: auto_recommended\nowner: ai_recommender\n";

    /**
     * Generate the YAML DSL for the given recommendations.
     *
     * @param recommendations recommendations (null → empty features list)
     * @return YAML text
     */
    public String generate(List<FeatureRecommendation> recommendations) {
        StringBuilder sb = new StringBuilder(HEADER);
        if (recommendations == null || recommendations.isEmpty()) {
            sb.append("features: []\n");
            return sb.toString();
        }
        sb.append("features:\n");
        for (FeatureRecommendation r : recommendations) {
            sb.append("  - name: ").append(r.featureName()).append('\n');
            sb.append("    type: double\n");
            sb.append("    transform: auto\n");
            sb.append("    aggregation: sum\n");
            sb.append("    window: 30d\n");
            sb.append("    recommended_by: ").append(r.source()).append('\n');
            sb.append("    confidence: ").append(formatScore(r.score())).append('\n');
        }
        return sb.toString();
    }

    private static String formatScore(double score) {
        // Trim trailing zeros while keeping at least one decimal place.
        String s = String.format(java.util.Locale.ROOT, "%.4f", score);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) {
            s = s + "0";
        }
        return s;
    }
}
