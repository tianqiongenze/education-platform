package com.ep.bff.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link OrderWarmupClient} that warms the order-detail cache by calling the
 * order-platform order-detail HTTP endpoint (D-228).
 * <p>
 * A plain GET on {@code /api/v1/orders/{id}} (same path as the BFF's
 * {@code OrderDubboController} mapping, resolved against order-platform's own
 * {@code OrderController}) is enough: order-platform caches order detail
 * internally via ep-cache ({@code OrderServiceImpl#getOrderById} →
 * {@code cacheService.getOrLoad(CACHE_ORDER, ...)}), so fetching the freshly
 * paid order fills the cache before the first user request arrives. Mirrors
 * {@link HttpCourseWarmupClient}: keeps portal-bff decoupled from
 * order-platform's cache internals and is trivially testable/mockable.
 * <p>
 * Only instantiated when {@code ep.event-consumer.enabled=true} (default
 * {@code false}) — no runtime HTTP or Pulsar dependency unless explicitly
 * enabled. Warm-up failures are logged and swallowed: warm-up is an
 * optimization, never a correctness path.
 */
@Component
@ConditionalOnProperty(name = "ep.event-consumer.enabled", havingValue = "true", matchIfMissing = false)
public class HttpOrderWarmupClient implements OrderWarmupClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOrderWarmupClient.class);

    private final RestClient restClient;

    public HttpOrderWarmupClient(
            @Value("${ep.event-consumer.order-platform-base-url:http://order-platform:8084}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    HttpOrderWarmupClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void warmupOrderDetail(long orderId) {
        try {
            restClient.get()
                    .uri("/api/v1/orders/{id}", orderId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("order detail cache warmed: orderId={}", orderId);
        } catch (Exception e) {
            log.warn("order detail cache warmup failed (best-effort, ignored): orderId={} error={}",
                    orderId, e.getMessage());
        }
    }
}
