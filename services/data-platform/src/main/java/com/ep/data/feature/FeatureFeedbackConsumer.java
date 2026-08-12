package com.ep.data.feature;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Consumes SHAP feedback events from the model serving layer and converts them
 * into a {@link FeatureImportanceUpdate} (§12.7).
 *
 * <p>Event JSON shape (SDD §12.7):
 * <pre>{@code
 * {"model_id":"m1","feature_importance":[{"name":"f1","shap":0.5},{"name":"f2","shap":0.3}]}
 * }</pre>
 *
 * <p>Processing:
 * <ol>
 *   <li>Parse the JSON into a {@link FeatureFeedbackEvent}.</li>
 *   <li>Sort features by SHAP value descending and assign 1-based ranks.</li>
 *   <li>Identify the bottom 20% (floor, minimum 1 when non-empty) as
 *       candidates for lazy-load demotion (§12.2.3).</li>
 * </ol>
 *
 * <p>This component receives the raw JSON string and does not need a Pulsar
 * connection; the {@code EP_DEV_ONLY} guard still gates any future
 * infrastructure integration.
 */
@Component
public class FeatureFeedbackConsumer {

    private static final Logger log = LoggerFactory.getLogger(FeatureFeedbackConsumer.class);

    private static final double BOTTOM_FRACTION = 0.2;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule());


    /**
     * Parse and process a SHAP feedback event.
     *
     * @param jsonEvent raw JSON event string
     * @return processed update (empty when the input is null/empty/invalid)
     */
    public FeatureImportanceUpdate consume(String jsonEvent) {
        if (jsonEvent == null || jsonEvent.isBlank()) {
            log.info("[FeatureFeedbackConsumer] empty/null event ignored");
            return emptyUpdate();
        }

        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);

        FeatureFeedbackEvent event;
        try {
            event = objectMapper.readValue(jsonEvent, FeatureFeedbackEvent.class);
        } catch (Exception e) {
            log.error("[FeatureFeedbackConsumer] failed to parse SHAP feedback event: {}", e.getMessage());
            return emptyUpdate();
        }

        if (devGuard) {
            log.info("[FeatureFeedbackConsumer] EP_DEV_ONLY active — parsed event for modelId={} ({} features)",
                    event.modelId(),
                    event.featureImportance() == null ? 0 : event.featureImportance().size());
        }

        if (event.featureImportance() == null || event.featureImportance().isEmpty()) {
            return new FeatureImportanceUpdate(event.modelId(), List.of(), Set.of());
        }

        // Sort by SHAP descending; assign 1-based rank.
        List<FeatureShap> sorted = new ArrayList<>(event.featureImportance());
        sorted.sort(Comparator.comparingDouble(FeatureShap::shap).reversed());

        LocalDateTime now = LocalDateTime.now();
        List<FeatureImportance> rankings = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            FeatureShap fs = sorted.get(i);
            rankings.add(new FeatureImportance(fs.name(), fs.shap(), i + 1, event.modelId(), now));
        }

        Set<String> bottomFeatures = bottomNPercent(rankings, BOTTOM_FRACTION);

        return new FeatureImportanceUpdate(event.modelId(), rankings, bottomFeatures);
    }

    /**
     * Compute the bottom {@code fraction} of features by SHAP value. The count
     * is {@code floor(size * fraction)} with a minimum of 1 when the list is
     * non-empty.
     *
     * @param rankings importance list (already sorted by SHAP desc)
     * @param fraction fraction in [0,1]
     * @return names of the bottom features
     */
    private static Set<String> bottomNPercent(List<FeatureImportance> rankings, double fraction) {
        if (rankings.isEmpty()) {
            return Set.of();
        }
        int count = (int) Math.floor(rankings.size() * fraction);
        if (count < 1) {
            count = 1;
        }
        if (count > rankings.size()) {
            count = rankings.size();
        }
        // rankings are sorted descending → bottom is the tail.
        Set<String> bottom = new HashSet<>();
        for (int i = rankings.size() - count; i < rankings.size(); i++) {
            String name = rankings.get(i).featureName();
            if (name != null) {
                bottom.add(name);
            }
        }
        return bottom;
    }

    private static FeatureImportanceUpdate emptyUpdate() {
        return new FeatureImportanceUpdate(null, List.of(), Set.of());
    }
}
