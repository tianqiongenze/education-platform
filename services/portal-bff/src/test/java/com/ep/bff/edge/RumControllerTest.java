package com.ep.bff.edge;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-235 tests: RUM ingest validation, cardinality-safe metering, telemetry
 * never errors back at the client.
 */
class RumControllerTest {

    private SimpleMeterRegistry registry;
    private RumController controller;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        controller = new RumController(registry);
    }

    private Map<String, Object> payload(String name, double value, String rating) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("value", value);
        m.put("rating", rating);
        m.put("route", "/courses/42");
        m.put("id", "v1-123");
        return m;
    }

    @Test
    @DisplayName("valid LCP beacon is recorded as a distribution summary")
    void validVitalRecorded() {
        ResponseEntity<Void> resp = controller.ingest(payload("LCP", 2450.0, "needs-improvement"));
        assertEquals(204, resp.getStatusCode().value());
        assertEquals(1, registry.get("ep_rum_web_vital").tag("metric", "LCP").summary().count());
    }

    @Test
    @DisplayName("unknown metric names are dropped silently")
    void unknownMetricDropped() {
        controller.ingest(payload("FID", 100.0, "good")); // FID is legacy, not accepted
        assertEquals(0, registry.find("ep_rum_web_vital").summaries().size());
    }

    @Test
    @DisplayName("insane values (negative / > 60s / NaN) are dropped")
    void insaneValuesDropped() {
        controller.ingest(payload("LCP", -5.0, "good"));
        controller.ingest(payload("LCP", 120_000.0, "poor"));
        Map<String, Object> bad = new HashMap<>();
        bad.put("name", "LCP");
        bad.put("value", "not-a-number");
        controller.ingest(bad);
        assertEquals(0, registry.find("ep_rum_web_vital").summaries().size());
    }

    @Test
    @DisplayName("string values are parsed leniently")
    void stringValueParsed() {
        Map<String, Object> m = payload("CLS", 0, "good");
        m.put("value", "0.08");
        controller.ingest(m);
        assertEquals(1, registry.get("ep_rum_web_vital").tag("metric", "CLS").summary().count());
    }

    @Test
    @DisplayName("route is not a metric tag (cardinality safety)")
    void routeNotATag() {
        controller.ingest(payload("LCP", 2000.0, "good"));
        var tags = registry.get("ep_rum_web_vital").summary().getId().getTags();
        assertTrue(tags.stream().noneMatch(t -> t.getKey().equals("route")),
                "unbounded route cardinality would explode Prometheus series");
    }

    @Test
    @DisplayName("malformed payload never errors back (204 always)")
    void malformedNeverErrors() {
        Map<String, Object> garbage = new HashMap<>();
        garbage.put("name", 42);
        garbage.put("value", new Object());
        ResponseEntity<Void> resp = controller.ingest(garbage);
        assertEquals(204, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("all five vitals are accepted")
    void allVitalsAccepted() {
        for (String name : new String[]{"LCP", "INP", "CLS", "TTFB", "FCP"}) {
            assertEquals(204, controller.ingest(payload(name, 100.0, "good")).getStatusCode().value());
        }
        assertEquals(5, registry.find("ep_rum_web_vital").summaries().size());
    }
}
