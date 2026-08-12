package com.ep.data.drift;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Publishes drift alerts to the feedback bus (§12.7).
 *
 * <p>JSON shape:
 * <pre>{@code
 * {"feature_name":"...","psi":0.25,"alert_level":"ALERT","detected_at":"2026-08-08T..."}
 * }</pre>
 *
 * <p>{@code alert_level} mapping:
 * <ul>
 *   <li>{@code "NORMAL"} when not an alert.</li>
 *   <li>{@code "ALERT"} when {@code isAlert && !isSevere}.</li>
 *   <li>{@code "SEVERE"} when {@code isSevere}.</li>
 * </ul>
 *
 * <p>In dev/CI ({@code EP_DEV_ONLY} active) the JSON is logged and returned
 * without a real Pulsar connection. In production the JSON would be published
 * to {@code persistent://public/feedback/drift-alert} (not implemented in
 * MVP — logged only).
 */
@Component
public class DriftAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(DriftAlertPublisher.class);

    private static final String TOPIC = "persistent://public/feedback/drift-alert";

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * Serialize the drift result and "publish" the alert.
     *
     * @param result drift result (null → "{}")
     * @return the JSON payload
     */
    public String publishAlert(DriftResult result) {
        if (result == null) {
            log.info("[DriftAlertPublisher] null result — returning empty JSON");
            return "{}";
        }

        String alertLevel = alertLevel(result);
        String detectedAt = result.detectedAt() == null ? null : result.detectedAt().toString();

        String json;
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("feature_name", result.featureName());
            node.put("psi", result.psi());
            node.put("alert_level", alertLevel);
            if (detectedAt != null) {
                node.put("detected_at", detectedAt);
            }
            json = objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("[DriftAlertPublisher] failed to serialize drift result: {}", e.getMessage());
            return "{}";
        }

        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);
        if (devGuard) {
            log.info("[DriftAlertPublisher] EP_DEV_ONLY active — simulated publish to {}: {}", TOPIC, json);
        } else {
            // Production: would publish to Pulsar topic (not implemented in MVP).
            log.info("[DriftAlertPublisher] production publish to {}: {}", TOPIC, json);
        }
        return json;
    }

    private static String alertLevel(DriftResult result) {
        if (result.isSevere()) {
            return "SEVERE";
        }
        if (result.isAlert()) {
            return "ALERT";
        }
        return "NORMAL";
    }
}
