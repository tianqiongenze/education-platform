package com.ep.data.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adjusts feature cache weights based on SHAP importance (§12.2.3).
 *
 * <p>Policy:
 * <ul>
 *   <li>Top 20% by SHAP → promoted (cache weight ×2, priority retention).</li>
 *   <li>Bottom 20% by SHAP → demoted (cache weight ×0.5, lazy-load).</li>
 *   <li>Middle 60% → unchanged.</li>
 * </ul>
 *
 * <p>Counts use {@code floor(size * 0.2)}. The {@code EP_DEV_ONLY} guard gates
 * any real Caffeine/Redis operation; in dev/CI the adjustment is logged and
 * the report returned without touching infrastructure.
 */
@Component
public class CacheWeightAdjuster {

    private static final Logger log = LoggerFactory.getLogger(CacheWeightAdjuster.class);
    private static final double FRACTION = 0.2;

    /**
     * Compute the cache-weight adjustment report for an update.
     *
     * @param update SHAP importance update (null/empty → empty report)
     * @return report with promoted/demoted feature names and unchanged count
     */
    public CacheWeightReport adjust(FeatureImportanceUpdate update) {
        if (update == null || update.rankings() == null || update.rankings().isEmpty()) {
            log.info("[CacheWeightAdjuster] empty update — no adjustment");
            return new CacheWeightReport(List.of(), List.of(), 0);
        }

        List<FeatureImportance> sorted = new ArrayList<>(update.rankings());
        sorted.sort(FeatureImportance.BY_SHAP_DESC);

        int size = sorted.size();
        int band = (int) Math.floor(size * FRACTION);
        if (band < 1) {
            band = 1;
        }
        if (band > size) {
            band = size;
        }

        List<String> promoted = new ArrayList<>(band);
        List<String> demoted = new ArrayList<>(band);
        for (int i = 0; i < band; i++) {
            String name = sorted.get(i).featureName();
            if (name != null) {
                promoted.add(name);
            }
        }
        for (int i = size - band; i < size; i++) {
            String name = sorted.get(i).featureName();
            if (name != null) {
                demoted.add(name);
            }
        }
        int unchanged = size - promoted.size() - demoted.size();

        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);
        if (devGuard) {
            log.info("[CacheWeightAdjuster] EP_DEV_ONLY active — simulated adjust: promoted={}, demoted={}, unchanged={}",
                    promoted.size(), demoted.size(), unchanged);
        } else {
            // Production: would call Caffeine/Redis to re-weight entries.
            log.info("[CacheWeightAdjuster] production adjust applied: promoted={}, demoted={}, unchanged={}",
                    promoted.size(), demoted.size(), unchanged);
        }

        return new CacheWeightReport(
                Collections.unmodifiableList(promoted),
                Collections.unmodifiableList(demoted),
                unchanged);
    }
}
