package com.ep.bff.edge;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * D-235: Real User Monitoring ingest endpoint — receives Core Web Vitals
 * (LCP / INP / CLS / TTFB / FCP) beaconed by the web apps' shared RUM SDK
 * ({@code @ep/shared/initRUM}) and records them as Micrometer
 * {@link DistributionSummary} histograms so field performance joins the same
 * Grafana stack as backend metrics.
 *
 * <p>Meters (per metric name × rating):
 * <ul>
 *   <li>{@code ep_rum_web_vital} — value distribution (p50/p95/p99 in Grafana)</li>
 *   <li>{@code ep_rum_web_vital_count} — implicit via the summary's count</li>
 * </ul>
 *
 * <p>Security/trust model: the payload is telemetry, not a business command —
 * validation is lenient (unknown metric names are dropped, values are
 * range-checked) and the endpoint is rate-limited by the shared
 * {@code RiskControlFilter} like every other API path. No PII is stored
 * (the SDK never sends user ids or query strings).</p>
 *
 * <p>Degrades to accept-and-log when Micrometer is absent (unit-test contexts).</p>
 */
@RestController
@RequestMapping("/api/v1/rum")
@ConditionalOnBean(MeterRegistry.class)
public class RumController {

    private static final Logger log = LoggerFactory.getLogger(RumController.class);

    /** Core Web Vitals + paint metrics accepted from the SDK. */
    private static final java.util.Set<String> KNOWN_METRICS =
            java.util.Set.of("LCP", "INP", "CLS", "TTFB", "FCP");

    /** Sanity bounds: outside these the beacon is client noise, drop it. */
    private static final double MAX_MS_VALUE = 60_000.0;

    private final MeterRegistry registry;

    public RumController(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Ingest one vital. Responds 204 regardless of metric validity (beacons
     * must never surface client-side errors) but only known, sane metrics
     * reach Micrometer.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> ingest(@RequestBody Map<String, Object> payload) {
        try {
            String name = String.valueOf(payload.getOrDefault("name", ""));
            String rating = String.valueOf(payload.getOrDefault("rating", "unknown"));
            double value = toDouble(payload.get("value"));
            String route = truncate(String.valueOf(payload.getOrDefault("route", "unknown")), 128);

            if (!KNOWN_METRICS.contains(name) || Double.isNaN(value) || value < 0 || value > MAX_MS_VALUE) {
                log.debug("[RumController] dropped invalid vital: name={} value={}", name, value);
                return ResponseEntity.noContent().build();
            }
            // Cardinality safety (Prometheus standard practice): the route path
            // is NOT a metric tag — unbounded route cardinality would explode
            // the series count. Route context stays in the sampled debug log.
            if (log.isDebugEnabled()) {
                log.debug("[RumController] vital name={} value={} rating={} route={}", name, value, rating, route);
            }
            DistributionSummary.builder("ep_rum_web_vital")
                    .description("Web Vitals value distribution from real users (D-235)")
                    .baseUnit("ms")
                    .tag("metric", name)
                    .tag("rating", rating)
                    .register(registry)
                    .record(value);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            // Telemetry must never error back at the client.
            log.debug("[RumController] ingest error: {}", e.getMessage());
            return ResponseEntity.noContent().build();
        }
    }

    private static double toDouble(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "unknown" : s;
        }
        return s.substring(0, max);
    }
}
