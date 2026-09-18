package com.ep.bff.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link CourseWarmupClient} that warms the course-detail cache by calling the
 * core-business course-detail HTTP endpoint (D-event-loop).
 * <p>
 * A plain GET is enough: core-business caches course detail internally
 * (ep-cache L1/L2), so fetching the freshly published course fills the cache —
 * chosen over the CacheWarmer SPI because it keeps portal-bff decoupled from
 * core-business' cache internals and is trivially testable/mockable.
 * <p>
 * Only instantiated when {@code ep.event-consumer.enabled=true} (default
 * {@code false}) — no runtime HTTP or Pulsar dependency unless explicitly
 * enabled. Warm-up failures are logged and swallowed: warm-up is an
 * optimization, never a correctness path.
 */
@Component
@ConditionalOnProperty(name = "ep.event-consumer.enabled", havingValue = "true", matchIfMissing = false)
public class HttpCourseWarmupClient implements CourseWarmupClient {

    private static final Logger log = LoggerFactory.getLogger(HttpCourseWarmupClient.class);

    private final RestClient restClient;

    public HttpCourseWarmupClient(
            @Value("${ep.event-consumer.core-business-base-url:http://core-business:8083}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    HttpCourseWarmupClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void warmupCourseDetail(long courseId) {
        try {
            restClient.get()
                    .uri("/api/v1/courses/{id}", courseId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("course detail cache warmed: courseId={}", courseId);
        } catch (Exception e) {
            log.warn("course detail cache warmup failed (best-effort, ignored): courseId={} error={}",
                    courseId, e.getMessage());
        }
    }
}
