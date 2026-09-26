package com.ep.bff.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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
 * D-228: consumes {@code CourseArchivedEvent} from the core-business
 * domain-events topic and refreshes the course-detail cache so the
 * pre-archive state is not served after the course went offline
 * (event consumer matrix extension, mirrors {@link CoursePublishedConsumer}).
 * <p>
 * Subscription model: {@link SubscriptionType#Shared} with its own stable
 * subscription name ({@code portal-bff-course-evict} — distinct from the
 * course-warmup subscription so every BFF consumer receives every event
 * independently). The refresh side effect is idempotent, so any single pod of
 * the group handling a message is sufficient.
 * <p>
 * Eviction semantics: core-business exposes no cache-invalidation endpoint and
 * already evicts the course L1/L2 synchronously inside its archive
 * transaction, so "evict" here is implemented as a detail GET that reloads the
 * archived state into the cache (see {@link CourseWarmupClient#evictCourseDetail(long)}).
 * <p>
 * Payload note (same limitation as {@link CoursePublishedConsumer}): producers
 * serialize the plain event record JSON with no {@code eventType} header yet,
 * and {@code CourseArchivedEvent} shares the exact shape of
 * {@code CoursePublishedEvent} ({@code aggregateId/courseId/teacherId/occurredAt}).
 * Both therefore drive the refresh GET, which is harmless/idempotent for a
 * published course; {@code UserFrozenEvent} (userId+reason, no courseId) is
 * recognized by shape and skipped at DEBUG — it legitimately shares this topic,
 * so it is expected traffic, not a data problem. A course-shaped payload that
 * carries no {@code courseId} is a genuine completeness problem and is logged
 * at WARN. Once publishers attach {@code eventType} headers this consumer will
 * filter precisely on {@code CourseArchivedEvent}.
 * <p>
 * Reliability: every message is acknowledged in a {@code finally} block — a
 * poison pill cannot block the subscription; evict failures are logged and
 * swallowed (cache coherence is an optimization, not a correctness path).
 * <p>
 * EP_DEV_ONLY guard: the whole bean exists only when
 * {@code ep.event-consumer.enabled=true} (default {@code false}), and if no
 * {@link PulsarClient} bean is available it degrades to a no-op without
 * throwing — mirroring {@code PulsarCacheEvictionConsumer} in ep-cache.
 */
@Component
@ConditionalOnProperty(name = "ep.event-consumer.enabled", havingValue = "true", matchIfMissing = false)
public class CourseArchivedConsumer {

    private static final Logger log = LoggerFactory.getLogger(CourseArchivedConsumer.class);

    private final ObjectProvider<PulsarClient> clientProvider;
    private final CourseWarmupClient warmupClient;
    private final String topic;
    private final String subscription;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private volatile Consumer<byte[]> consumer;

    public CourseArchivedConsumer(ObjectProvider<PulsarClient> clientProvider,
                                  CourseWarmupClient warmupClient,
                                  @Value("${ep.event-consumer.topic:persistent://public/default/ep-business-domain-events}")
                                  String topic,
                                  @Value("${ep.event-consumer.course-evict-subscription:portal-bff-course-evict}")
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
            log.debug("Pulsar client unavailable, CourseArchivedConsumer degrades to no-op (EP_DEV_ONLY)");
            return;
        }
        try {
            consumer = client.newConsumer()
                    .topic(topic)
                    .subscriptionName(subscription)
                    .subscriptionType(SubscriptionType.Shared)
                    .messageListener(this::onMessage)
                    .subscribe();
            log.info("CourseArchivedConsumer subscribed topic={} subscription={}", topic, subscription);
        } catch (Exception e) {
            // EP_DEV_ONLY: broker down, auth failed, etc. — degrade silently.
            log.debug("CourseArchivedConsumer subscribe failed (EP_DEV_ONLY), topic={}: {}", topic, e.getMessage());
            consumer = null;
        }
    }

    private void onMessage(Consumer<byte[]> c, Message<byte[]> msg) {
        handleMessage(c, msg);
    }

    /**
     * Package-private entry point for the {@link MessageListener} callback and
     * unit tests. Parses the JSON payload and delegates to
     * {@link CourseWarmupClient#evictCourseDetail(long)}. Always acknowledges
     * the message so a poison pill cannot block the subscription.
     */
    void handleMessage(Consumer<byte[]> c, Message<byte[]> msg) {
        try {
            if (msg != null && msg.getData() != null && msg.getData().length > 0) {
                CourseArchivedPayload payload =
                        mapper.readValue(msg.getData(), CourseArchivedPayload.class);
                if (payload != null && payload.courseId() != null && payload.courseId() > 0) {
                    warmupClient.evictCourseDetail(payload.courseId());
                } else if (isUserFrozenShape(msg.getData())) {
                    // Expected traffic: UserFrozenEvent shares this topic and has no courseId.
                    log.debug("user-frozen event on domain-events topic, skipping course evict");
                } else {
                    log.warn("course archived message carries no courseId (skipped): aggregateId={}",
                            payload == null ? null : payload.aggregateId());
                }
            }
        } catch (Exception e) {
            log.warn("course archived message handling failed (skipped): {}", e.getMessage());
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
     * True when the payload looks like a {@code UserFrozenEvent}
     * ({@code userId}+{@code reason} present, {@code courseId} absent) — the
     * expected co-tenant event shape on the shared domain-events topic.
     */
    private boolean isUserFrozenShape(byte[] data) {
        try {
            JsonNode node = mapper.readTree(data);
            return node != null && node.hasNonNull("userId") && node.hasNonNull("reason")
                    && !node.has("courseId");
        } catch (Exception e) {
            return false;
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
     * DTO for the {@code CourseArchivedEvent} JSON payload
     * ({@code {"aggregateId":1,"courseId":1,"teacherId":2,"occurredAt":"…"}}).
     * {@code occurredAt} is kept as String to avoid coupling the consumer to a
     * JSR-310 mapper setup; it is not needed for the cache refresh.
     */
    record CourseArchivedPayload(Long aggregateId, Long courseId, Long teacherId, String occurredAt) {
    }
}
