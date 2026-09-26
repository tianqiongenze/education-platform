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
 * D-228: consumes {@code UserFrozenEvent} from the core-business domain-events
 * topic and writes a structured AUDIT log entry (risk-control P1-8
 * continuation, event-driven side) — the BFF-side record that a user was
 * frozen, even when the freeze did not originate from an HTTP request.
 * <p>
 * Subscription model: {@link SubscriptionType#Shared} with its own stable
 * subscription name ({@code portal-bff-user-frozen-audit}) so this consumer
 * receives every event independently of the course consumers. Audit writing is
 * idempotent and side-effect free, so any single pod of the group handling a
 * message is sufficient.
 * <p>
 * <b>Future session-invalidation hook (注销会话钩子):</b> a frozen user should
 * lose active access immediately. portal-bff currently issues stateless JWTs
 * (no server-side session store, see {@code com.ep.bff.auth.JwtService}), so
 * there is nothing to revoke in-process yet. When a session/denylist store
 * lands (e.g. a token denylist in ep-cache keyed by userId, or short-lived
 * sessions), this consumer is the designated hook point: add the revocation
 * call next to {@link EventAuditLogger#userFrozen(Long, String, String)} in
 * {@link #handleMessage}. Until then the AUDIT entry is the only BFF-side
 * action — downstream authorization must check the user's frozen status
 * (core-business already enforces it per request).
 * <p>
 * Payload note: producers serialize the plain event record JSON with no
 * {@code eventType} header yet, so co-tenant course events
 * ({@code CoursePublishedEvent}/{@code CourseArchivedEvent}, which carry a
 * {@code courseId} but no {@code userId}) are recognized by shape and skipped
 * at DEBUG — they legitimately share this topic. A payload with neither
 * {@code userId} nor {@code courseId} is unrecognized/incomplete and is logged
 * at WARN (missing-field tolerance: skip, never crash). Once publishers attach
 * {@code eventType} headers this consumer will filter precisely on
 * {@code UserFrozenEvent}.
 * <p>
 * Reliability: every message is acknowledged in a {@code finally} block — a
 * poison pill cannot block the subscription; audit failures are logged and
 * swallowed.
 * <p>
 * EP_DEV_ONLY guard: the whole bean exists only when
 * {@code ep.event-consumer.enabled=true} (default {@code false}), and if no
 * {@link PulsarClient} bean is available it degrades to a no-op without
 * throwing — mirroring {@link CoursePublishedConsumer}.
 */
@Component
@ConditionalOnProperty(name = "ep.event-consumer.enabled", havingValue = "true", matchIfMissing = false)
public class UserFrozenConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserFrozenConsumer.class);

    private final ObjectProvider<PulsarClient> clientProvider;
    private final String topic;
    private final String subscription;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private volatile Consumer<byte[]> consumer;

    public UserFrozenConsumer(ObjectProvider<PulsarClient> clientProvider,
                              @Value("${ep.event-consumer.topic:persistent://public/default/ep-business-domain-events}")
                              String topic,
                              @Value("${ep.event-consumer.user-frozen-subscription:portal-bff-user-frozen-audit}")
                              String subscription) {
        this.clientProvider = clientProvider;
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
            log.debug("Pulsar client unavailable, UserFrozenConsumer degrades to no-op (EP_DEV_ONLY)");
            return;
        }
        try {
            consumer = client.newConsumer()
                    .topic(topic)
                    .subscriptionName(subscription)
                    .subscriptionType(SubscriptionType.Shared)
                    .messageListener(this::onMessage)
                    .subscribe();
            log.info("UserFrozenConsumer subscribed topic={} subscription={}", topic, subscription);
        } catch (Exception e) {
            // EP_DEV_ONLY: broker down, auth failed, etc. — degrade silently.
            log.debug("UserFrozenConsumer subscribe failed (EP_DEV_ONLY), topic={}: {}", topic, e.getMessage());
            consumer = null;
        }
    }

    private void onMessage(Consumer<byte[]> c, Message<byte[]> msg) {
        handleMessage(c, msg);
    }

    /**
     * Package-private entry point for the {@link MessageListener} callback and
     * unit tests. Parses the JSON payload and delegates to
     * {@link EventAuditLogger#userFrozen(Long, String, String)}. Always
     * acknowledges the message so a poison pill cannot block the subscription.
     */
    void handleMessage(Consumer<byte[]> c, Message<byte[]> msg) {
        try {
            if (msg != null && msg.getData() != null && msg.getData().length > 0) {
                UserFrozenPayload payload =
                        mapper.readValue(msg.getData(), UserFrozenPayload.class);
                if (payload != null && payload.userId() != null && payload.userId() > 0) {
                    EventAuditLogger.userFrozen(payload.userId(), payload.reason(), payload.occurredAt());
                } else if (isCourseShape(msg.getData())) {
                    // Expected traffic: course events share this topic and have no userId.
                    log.debug("course event on domain-events topic, skipping user-frozen audit");
                } else {
                    log.warn("user frozen message carries no userId (audit skipped): aggregateId={}",
                            payload == null ? null : payload.aggregateId());
                }
            }
        } catch (Exception e) {
            log.warn("user frozen message handling failed (skipped): {}", e.getMessage());
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
     * True when the payload looks like a course event ({@code courseId}
     * present) — the expected co-tenant event shape on the shared
     * domain-events topic.
     */
    private boolean isCourseShape(byte[] data) {
        try {
            JsonNode node = mapper.readTree(data);
            return node != null && node.has("courseId");
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
     * DTO for the {@code UserFrozenEvent} JSON payload
     * ({@code {"aggregateId":9,"userId":9,"reason":"fraud","occurredAt":"…"}}).
     * {@code occurredAt} is kept as String to avoid coupling the consumer to a
     * JSR-310 mapper setup; it is logged verbatim in the audit entry.
     */
    record UserFrozenPayload(Long aggregateId, Long userId, String reason, String occurredAt) {
    }
}
