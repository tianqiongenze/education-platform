package com.ep.bff.edge;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-243 tests: SLA endpoint verdicts — healthy, breaching, and
 * insufficient-sample paths.
 */
class SlaControllerTest {

    private SimpleMeterRegistry registry;
    private SlaController controller;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        controller = new SlaController(registry);
    }

    private void sample(String cls, int count, double p95Ms, int serverErrors) {
        // publishPercentiles mirrors application.yml
        // (management.metrics.distribution.percentiles[http.server.requests])
        // that the SLA controller depends on for percentile() verdicts.
        Timer.builder("http.server.requests")
                .tag("class", cls)
                .tag("outcome", "SUCCESS")
                .publishPercentiles(0.95, 0.99)
                .register(registry);
        Timer.builder("http.server.requests")
                .tag("class", cls)
                .tag("outcome", "SERVER_ERROR")
                .publishPercentiles(0.95, 0.99)
                .register(registry);

        Timer total = registry.find("http.server.requests").tag("class", cls).timer();
        for (int i = 0; i < count; i++) {
            // 6.25% tail at the p95 level — ensures the 95th percentile lands
            // in the tail bucket (a strict 5% tail sorts *below* the p95 rank).
            double v = (i % 16 == 0) ? p95Ms : p95Ms * 0.2;
            total.record(Duration.ofNanos((long) (v * 1_000_000)));
        }
        Timer err = registry.find("http.server.requests")
                .tag("class", cls).tag("outcome", "SERVER_ERROR").timer();
        for (int i = 0; i < serverErrors; i++) {
            err.record(Duration.ofMillis(50));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> classVerdict(String cls) {
        ResponseEntity<Map<String, Object>> resp = controller.sla();
        assertEquals(200, resp.getStatusCode().value());
        Map<String, Object> classes = (Map<String, Object>) resp.getBody().get("classes");
        return (Map<String, Object>) classes.get(cls);
    }

    @Test
    @DisplayName("low-sample classes report insufficient-data instead of a verdict")
    void lowSampleInsufficientData() {
        Map<String, Object> readCls = classVerdict("read");
        assertEquals("insufficient-data", readCls.get("verdict"));
        assertEquals("low", readCls.get("sample"));
    }

    @Test
    @DisplayName("healthy p95 + low error rate → verdict ok, overall ok")
    void healthyVerdict() {
        sample("read", 500, 100, 2); // p95≈100ms < 500ms, err≈0.4% < 1%
        assertEquals("ok", classVerdict("read").get("verdict"));
        assertEquals("ok", controller.sla().getBody().get("overall"));
    }

    @Test
    @DisplayName("p95 above target → verdict breach, overall degraded")
    void breachVerdict() {
        sample("read", 500, 900, 0); // p95≈900ms > 500ms target
        assertEquals("breach", classVerdict("read").get("verdict"));
        assertEquals("degraded", controller.sla().getBody().get("overall"));
    }

    @Test
    @DisplayName("error rate above 1% breaches even with good latency")
    void highErrorRateBreaches() {
        sample("write", 500, 100, 10); // err = 10/510 ≈ 2% > 1%
        assertEquals("breach", classVerdict("write").get("verdict"));
    }

    @Test
    @DisplayName("all three SLA classes are reported")
    void allClassesPresent() {
        Map<String, Object> classes = (Map<String, Object>) controller.sla().getBody().get("classes");
        assertTrue(classes.containsKey("read"));
        assertTrue(classes.containsKey("write"));
        assertTrue(classes.containsKey("payment"));
    }
}
