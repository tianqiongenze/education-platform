package com.ep.data.drift;

import java.time.LocalDateTime;

/**
 * Result of a single feature drift detection (§12.2.2).
 *
 * @param featureName name of the monitored feature
 * @param psi         computed PSI value
 * @param threshold   alert threshold (0.2 per §12.2.2)
 * @param isAlert     true when {@code psi > 0.2}
 * @param isSevere    true when {@code psi > 0.4}
 * @param detectedAt  detection timestamp
 */
public record DriftResult(
        String featureName,
        double psi,
        double threshold,
        boolean isAlert,
        boolean isSevere,
        LocalDateTime detectedAt
) {
}
