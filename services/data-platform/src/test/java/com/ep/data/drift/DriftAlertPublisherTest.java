package com.ep.data.drift;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link DriftAlertPublisher}.
 */
class DriftAlertPublisherTest {

    private final DriftAlertPublisher publisher = new DriftAlertPublisher();

    private static DriftResult result(boolean isAlert, boolean isSevere) {
        return new DriftResult("f1", 0.25, 0.2, isAlert, isSevere, LocalDateTime.of(2026, 8, 8, 12, 0));
    }

    @Test
    void test_alert_level_mapping() {
        String json = publisher.publishAlert(result(true, false));
        assertTrue(json.contains("\"alert_level\":\"ALERT\""),
                "isAlert && !isSevere → ALERT, got " + json);
    }

    @Test
    void test_severe_level_mapping() {
        String json = publisher.publishAlert(result(true, true));
        assertTrue(json.contains("\"alert_level\":\"SEVERE\""),
                "isSevere → SEVERE, got " + json);
    }

    @Test
    void test_normal_level_mapping() {
        String json = publisher.publishAlert(result(false, false));
        assertTrue(json.contains("\"alert_level\":\"NORMAL\""),
                "!isAlert → NORMAL, got " + json);
    }

    @Test
    void test_json_contains_required_fields() {
        String json = publisher.publishAlert(result(true, false));
        assertTrue(json.contains("\"feature_name\""), "JSON should contain feature_name, got " + json);
        assertTrue(json.contains("\"psi\""), "JSON should contain psi, got " + json);
        assertTrue(json.contains("\"alert_level\""), "JSON should contain alert_level, got " + json);
        assertTrue(json.contains("\"detected_at\""), "JSON should contain detected_at, got " + json);
    }

    @Test
    void test_null_result_returns_empty_json() {
        assertEquals("{}", publisher.publishAlert(null), "null result → {}");
    }
}
