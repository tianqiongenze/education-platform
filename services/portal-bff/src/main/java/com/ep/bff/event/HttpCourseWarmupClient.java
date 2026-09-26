package com.ep.bff.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link CourseWarmupClient} that warms (and refreshes) the course-detail cache
 * by calling the core-business course-detail HTTP endpoint (D-event-loop).
 * <p>
 * A plain GET is enough: core-business caches course detail internally
 * (ep-cache L1/L2), so fetching the freshly published course fills the cache —
 * chosen over the CacheWarmer SPI because it keeps portal-bff decoupled from
 * core-business' cache internals and is trivially testable/mockable.
 * <p>
 * <b>Eviction semantics (D-228 trade-off):</b> core-business exposes no cache
 * invalidation HTTP endpoint (CourseController only offers detail/list/search
 * GETs plus publish/archive POSTs), and it already self-evicts the course
 * L1/L2 synchronously inside {@code CourseServiceImpl#archiveCourse}. A true
 * remote evict from the BFF is therefore neither available nor needed; the
 * closest safe capability is a detail GET, which reloads the (now archived)
 * state into the cache so the entry left over from before the state change is
 * replaced with fresh data — "evict as refresh". The GET is harmless/idempotent
 * for a published course too, so both course events on the shared domain-events
 * topic can safely drive it. Failures (e.g. a 404 for a concurrently deleted
 * course) are logged and swallowed: cache coherence is an optimization, never a
 * correctness path.
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

    /**
     * {@inheritDoc}
     * <p>
     * Implementation note (see class javadoc): executed as a detail GET that
     * repopulates the L1/L2 entry with the archived state — a refresh, not a
     * true remote evict, because core-business exposes no invalidation endpoint
     * and already evicts internally during the archive transaction.
     */
    @Override
    public void evictCourseDetail(long courseId) {
        try {
            restClient.get()
                    .uri("/api/v1/courses/{id}", courseId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("course detail cache refreshed (evict-as-reload): courseId={}", courseId);
        } catch (Exception e) {
            log.warn("course detail cache evict failed (best-effort, ignored): courseId={} error={}",
                    courseId, e.getMessage());
        }
    }
}
