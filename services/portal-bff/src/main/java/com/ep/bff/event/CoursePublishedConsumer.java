package com.ep.bff.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * D-event-loop: consumes {@code CoursePublishedEvent} from the core-business
 * domain-events topic and warms the course-detail cache, closing the
 * "events published but never consumed" audit gap.
 * <p>
 * Subscription model: {@link SubscriptionType#Shared} with a stable
 * subscription name — course warm-up is idempotent, so any single pod of the
 * group handling a message is sufficient (no per-pod fan-out needed, unlike
 * ep-cache's L1 eviction consumer).
 * <p>
 * Payload note: producers serialize the plain event record JSON (no
 * {@code eventType} header is attached yet — tracked for the next AsyncAPI
 * iteration), so the consumer distinguishes course events by payload shape:
 * {@code UserFrozenEvent} has no {@code courseId} and is skipped, while
 * {@code CoursePublishedEvent} and {@code CourseArchivedEvent} share the same
 * shape — both trigger a warm-up GET, which is harmless/idempotent for an
 * archived course. Once publishers attach {@code eventType} headers this
 * consumer will filter precisely on them.
 * <p>
 * Reliability: every message is acknowledged in a {@code finally} block — a
 * poison pill cannot block the subscription; warm-up failures are logged and
 * swallowed (warm-up is an optimization, not a correctness path).
 * <p>
 * EP_DEV_ONLY guard: the whole bean exists only when
 * {@code ep.event-consumer.enabled=true} (default {@code false}), and if no
 * {@link PulsarClient} bean is available it degrades to a no-op without
 * throwing — mirroring {@code PulsarCacheEvictionConsumer} in ep-cache.
 */
@Component
@ConditionalOnProperty(name = "ep.event-consumer.enabled", havingValue = "true", matchIfMissing = false)
public class CoursePublishedConsumer {

    private static final Logger log = LoggerFactory.getLogger(CoursePublishedConsumer.class);

    private final ObjectProvider<PulsarClient> clientProvider;
    private final CourseWarmupClient warmupClient;
    private final String topic;
    private final String subscription;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private volatile Consumer<byte[]> consumer;

    public CoursePublishedConsumer(ObjectProvider<PulsarClient> clientProvider,
                                   CourseWarmupClient warmupClient,
                                   @Value("${ep.event-consumer.topic:persistent://public/default/ep-business-domain-events}")
                                   String topic,
                                   @Value("${ep.event-consumer.subscription:portal-bff-course-warmup}")
                                   String subscription) {
        this.clientProvider = clientProvider;
        this.warmupClient = warmupClient;
        this.topic = topic;
        this.subscription = subscription;
    }

    /**
     * Subscribe to the domain-events topic. EP_DEV_ONLY safe: missing
     * {@link PulsarClient} or failed subscribe degrades to a no-op.
     */
    @PostConstruct
    public void start() {
        PulsarClient client = clientProvider.getIfAvailable();
        if (client == null) {
            log.debug("Pulsar client unavailable, CoursePublishedConsumer degrades to no-op (EP_DEV_ONLY)");
            return;
        }
        try {
            consumer = client.newConsumer()
                    .topic(topic)
                    .subscriptionName(subscription)
                    .subscriptionType(SubscriptionType.Shared)
                    .messageListener(this::onMessage)
                    .subscribe();
            log.info("CoursePublishedConsumer subscribed topic={} subscription={}", topic, subscription);
        } catch (Exception e) {
            // EP_DEV_ONLY: broker down, auth failed, etc. — degrade silently.
            log.debug("CoursePublishedConsumer subscribe failed (EP_DEV_ONLY), topic={}: {}", topic, e.getMessage());
            consumer = null;
        }
    }

    private void onMessage(Consumer<byte[]> c, Message<byte[]> msg) {
        handleMessage(c, msg);
    }

    /**
     * Package-private entry point for the {@link MessageListener} callback and
     * unit tests. Parses the JSON payload and delegates to
     * {@link CourseWarmupClient#warmupCourseDetail(long)}. Always acknowledges
     * the message so a poison pill cannot block the subscription.
     */
    void handleMessage(Consumer<byte[]> c, Message<byte[]> msg) {
        try {
            if (msg != null && msg.getData() != null && msg.getData().length > 0) {
                CoursePublishedPayload payload =
                        mapper.readValue(msg.getData(), CoursePublishedPayload.class);
                if (payload != null && payload.courseId() != null && payload.courseId() > 0) {
                    warmupClient.warmupCourseDetail(payload.courseId());
                } else {
                    log.debug("event payload carries no courseId, skipping warm-up");
                }
            }
        } catch (Exception e) {
            log.warn("course published message handling failed (skipped): {}", e.getMessage());
        } finally {
            try {
                if (c != null && msg != null) {
                    c.acknowledgeAsync(msg);
                }
            } catch (Exception e) {
                log.debug("pulsar ack fail: {}", e.getMessage());
            }
        }
    }

    /**
     * Close the consumer on shutdown. Best-effort; never throws.
     */
    @PreDestroy
    public void stop() {
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                log.debug("pulsar consumer close fail: {}", e.getMessage());
            }
        }
    }

    /**
     * DTO for the {@code CoursePublishedEvent} JSON payload
     * ({@code {"aggregateId":1,"courseId":1,"teacherId":2,"occurredAt":"…"}}).
     * {@code occurredAt} is kept as String to avoid coupling the consumer to a
     * JSR-310 mapper setup; it is not needed for warm-up.
     */
    record CoursePublishedPayload(Long aggregateId, Long courseId, Long teacherId, String occurredAt) {
    }
}
