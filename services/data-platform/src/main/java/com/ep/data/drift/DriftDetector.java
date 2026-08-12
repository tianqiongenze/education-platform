package com.ep.data.drift;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

/**
 * Detects feature distribution drift using PSI (§12.2.2).
 *
 * <p>Thresholds (per sp02-sdd §12.2.2):
 * <ul>
 *   <li>{@code psi > 0.2} — alert (significant drift)</li>
 *   <li>{@code psi > 0.4} — severe drift (action required)</li>
 * </ul>
 *
 * <p>Delegates the numeric PSI computation to {@link PSICalculator}.
 */
@Component
public class DriftDetector {

    /** PSI above this value triggers an alert (§12.2.2). */
    public static final double ALERT_THRESHOLD = 0.2;

    /** PSI above this value is classified as severe drift (§12.2.2). */
    public static final double SEVERE_THRESHOLD = 0.4;

    private final PSICalculator calculator = new PSICalculator();

    /**
     * Detect drift for a single feature.
     *
     * @param featureName feature name
     * @param baseline    baseline distribution sample
     * @param current     current distribution sample
     * @return drift result with PSI and alert/severe flags
     */
    public DriftResult detect(String featureName, double[] baseline, double[] current) {
        double psi = calculator.calculate(baseline, current);
        boolean isAlert = psi > ALERT_THRESHOLD;
        boolean isSevere = psi > SEVERE_THRESHOLD;
        return new DriftResult(
                featureName, psi, ALERT_THRESHOLD, isAlert, isSevere, LocalDateTime.now());
    }
}
