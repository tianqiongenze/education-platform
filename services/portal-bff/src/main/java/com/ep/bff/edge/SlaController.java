package com.ep.bff.edge;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * v8 P2 (D-243): user-facing SLA status endpoint — the data source for the
 * SLA dashboard the usability dimension flagged as missing.
 *
 * <p>Reads the JVM-local Micrometer state (the same meters Grafana scrapes)
 * and exposes a small, dashboard-friendly JSON: per-API-class p95 latency and
 * error rate over the process lifetime, plus an overall availability
 * estimate. Minimal and honest: no aggregation across pods (Prometheus/Grafana
 * remains the cross-pod source of truth) — this endpoint serves <i>per-pod
 * live state</i> for the SLA page and ops sanity checks.</p>
 *
 * <p>Latency classes follow the SLO sheet: read paths target p95 &lt; 500ms,
 * the payment path &lt; 2s. A class is "breaching" when p95 exceeds its
 * target with at least 100 samples (below that, noise dominates — the
 * endpoint reports {@code sample: low} instead of a verdict).</p>
 */
@RestController
@RequestMapping("/api/v1/sla")
@ConditionalOnBean(MeterRegistry.class)
public class SlaController {

    /** p95 targets per latency class (ms) — mirrors the SLO sheet G-01..G-10. */
    private static final Map<String, Double> P95_TARGETS_MS = Map.of(
            "read", 500.0,
            "write", 1000.0,
            "payment", 2000.0
    );

    /** Minimum samples before a verdict is emitted. */
    private static final double MIN_SAMPLES = 100.0;

    private final MeterRegistry registry;

    public SlaController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> sla() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "live");

        Map<String, Object> classes = new LinkedHashMap<>();
        boolean allHealthy = true;
        for (Map.Entry<String, Double> target : P95_TARGETS_MS.entrySet()) {
            Timer timer = findTimer(target.getKey());
            Map<String, Object> cls = new LinkedHashMap<>();
            if (timer == null || timer.count() < (long) MIN_SAMPLES) {
                cls.put("sample", "low");
                cls.put("count", timer == null ? 0 : timer.count());
                cls.put("verdict", "insufficient-data");
            } else {
                double p95 = timer.percentile(0.95, TimeUnit.MILLISECONDS);
                double errorRate = errorRateFor(target.getKey());
                cls.put("count", timer.count());
                cls.put("p95Ms", Math.round(p95 * 10) / 10.0);
                cls.put("targetP95Ms", target.getValue());
                cls.put("errorRate", Math.round(errorRate * 10000) / 10000.0);
                boolean healthy = p95 <= target.getValue() && errorRate <= 0.01;
                cls.put("verdict", healthy ? "ok" : "breach");
                allHealthy &= healthy;
            }
            classes.put(target.getKey(), cls);
        }
        out.put("classes", classes);
        out.put("overall", allHealthy ? "ok" : "degraded");
        return ResponseEntity.ok(out);
    }

    /**
     * Finds the success-path timer for the latency class. Deliberately
     * outcome=SUCCESS: error-request latency (usually fast 4xx/5xx) must not
     * dilute the p95 verdict — errors are judged separately via errorRate.
     * Also guards the multi-meter ambiguity of a bare search.
     */
    private Timer findTimer(String latencyClass) {
        Search search = registry.find("http.server.requests")
                .tag("class", latencyClass)
                .tag("outcome", "SUCCESS");
        return search != null ? search.timer() : null;
    }

    /**
     * Error rate = 5xx outcomes / total for the class. Spring's http.server
     * timers carry an "outcome" tag (SERVER_ERROR / CLIENT_ERROR / SUCCESS).
     */
    private double errorRateFor(String latencyClass) {
        Timer total = registry.find("http.server.requests").tag("class", latencyClass).timer();
        if (total == null || total.count() == 0) {
            return 0.0;
        }
        Timer serverErrors = registry.find("http.server.requests")
                .tag("class", latencyClass)
                .tag("outcome", "SERVER_ERROR")
                .timer();
        long errors = serverErrors == null ? 0 : serverErrors.count();
        return (double) errors / total.count();
    }
}
