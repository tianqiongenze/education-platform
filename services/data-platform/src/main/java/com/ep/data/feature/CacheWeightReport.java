package com.ep.data.feature;

import java.util.List;

/**
 * Result of cache weight adjustment (§12.2.3).
 *
 * @param promotedFeatures  top-20% features (cache weight ×2, priority retention)
 * @param demotedFeatures   bottom-20% features (cache weight ×0.5, lazy-load)
 * @param unchangedCount    middle 60% unchanged features count
 */
public record CacheWeightReport(
        List<String> promotedFeatures,
        List<String> demotedFeatures,
        int unchangedCount
) {
}
