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
 * D-228: consumes {@code OrderPaidEvent} from the order-platform events topic,
 * writes a structured AUDIT log entry (revenue/fulfilment trail) and warms the
 * order-detail cache so the freshly paid order is served from ep-cache
 * ({@code CACHE_ORDER}) when the buyer lands on the order page.
 * <p>
 * Subscription model: {@link SubscriptionType#Shared} with its own stable
 * subscription name ({@code portal-bff-order-paid-audit}). Both side effects
 * (audit write, warm-up GET) are idempotent, so any single pod of the group
 * handling a message is sufficient.
 * <p>
 * Payload note: order-platform publishes plain event record JSON with no
 * {@code eventType} header yet, and {@code OrderPaidEvent} shares most of its
 * shape with {@code OrderRefundedEvent} on the same topic. The two are
 * distinguished by field shape: an {@code OrderRefundedEvent} record carries no
 * {@code payMethod} field at all (Jackson also emits {@code "payMethod":null}
 * when a paid order has no pay method, so field presence — not value — is the
 * discriminator). Refund-shaped payloads are skipped at DEBUG (expected
 * co-tenant traffic, not a data problem); a payload without {@code orderId} is
 * unrecognized/incomplete and logged at WARN (missing-field tolerance: skip,
 * never crash). Once publishers attach {@code eventType} headers this consumer
 * will filter precisely on {@code OrderPaidEvent}.
 * <p>
 * Ordering of side effects: the AUDIT entry is written <b>before</b> the
 * warm-up call so a warm-up failure cannot cost the audit trail; a warm-up
 * failure is logged and swallowed (warm-up is an optimization, not a
 * correctness path).
 * <p>
 * Reliability: every message is acknowledged in a {@code finally} block — a
 * poison pill cannot block the subscription.
 * <p>
 * EP_DEV_ONLY guard: the whole bean exists only when
 * {@code ep.event-consumer.enabled=true} (default {@code false}), and if no
 * {@link PulsarClient} bean is available it degrades to a no-op without
 * throwing — mirroring {@link CoursePublishedConsumer}.
 */
@Component
@ConditionalOnProperty(name = "ep.event-consumer.enabled", havingValue = "true", matchIfMissing = false)
public class OrderPaidConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidConsumer.class);

    private final ObjectProvider<PulsarClient> clientProvider;
    private final OrderWarmupClient orderWarmupClient;
    private final String topic;
    private final String subscription;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private volatile Consumer<byte[]> consumer;

    public OrderPaidConsumer(ObjectProvider<PulsarClient> clientProvider,
                             OrderWarmupClient orderWarmupClient,
                             @Value("${ep.event-consumer.order-topic:persistent://public/default/order-platform-events}")
                             String topic,
                             @Value("${ep.event-consumer.order-paid-subscription:portal-bff-order-paid-audit}")
                             String subscription) {
        this.clientProvider = clientProvider;
        this.orderWarmupClient = orderWarmupClient;
        this.topic = topic;
        this.subscription = subscription;
    }

    /**
     * Subscribe to the order-platform events topic. EP_DEV_ONLY safe: missing
     * {@link PulsarClient} or failed subscribe degrades to a no-op.
     */
    @PostConstruct
    public void start() {
        PulsarClient client = clientProvider.getIfAvailable();
        if (client == null) {
            log.debug("Pulsar client unavailable, OrderPaidConsumer degrades to no-op (EP_DEV_ONLY)");
            return;
        }
        try {
            consumer = client.newConsumer()
                    .topic(topic)
                    .subscriptionName(subscription)
                    .subscriptionType(SubscriptionType.Shared)
                    .messageListener(this::onMessage)
                    .subscribe();
            log.info("OrderPaidConsumer subscribed topic={} subscription={}", topic, subscription);
        } catch (Exception e) {
            // EP_DEV_ONLY: broker down, auth failed, etc. — degrade silently.
            log.debug("OrderPaidConsumer subscribe failed (EP_DEV_ONLY), topic={}: {}", topic, e.getMessage());
            consumer = null;
        }
    }

    private void onMessage(Consumer<byte[]> c, Message<byte[]> msg) {
        handleMessage(c, msg);
    }

    /**
     * Package-private entry point for the {@link MessageListener} callback and
     * unit tests. Parses the JSON payload, delegates to
     * {@link EventAuditLogger#orderPaid(Long, Long, Long, String, String)} and
     * then to {@link OrderWarmupClient#warmupOrderDetail(long)}. Always
     * acknowledges the message so a poison pill cannot block the subscription.
     */
    void handleMessage(Consumer<byte[]> c, Message<byte[]> msg) {
        try {
            if (msg != null && msg.getData() != null && msg.getData().length > 0) {
                OrderPaidPayload payload =
                        mapper.readValue(msg.getData(), OrderPaidPayload.class);
                if (payload != null && payload.orderId() != null && payload.orderId() > 0) {
                    if (isOrderRefundedShape(msg.getData())) {
                        // Expected traffic: OrderRefundedEvent shares this topic
                        // and has no payMethod field — not an ORDER_PAID event.
                        log.debug("order-refunded event on order topic, skipping paid handling");
                    } else {
                        // Audit first: a warm-up failure must not cost the audit trail.
                        EventAuditLogger.orderPaid(payload.orderId(), payload.userId(),
                                payload.courseId(), payload.productType(), payload.occurredAt());
                        orderWarmupClient.warmupOrderDetail(payload.orderId());
                    }
                } else {
                    log.warn("order paid message carries no orderId (skipped): aggregateId={}",
                            payload == null ? null : payload.aggregateId());
                }
            }
        } catch (Exception e) {
            log.warn("order paid message handling failed (skipped): {}", e.getMessage());
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
     * True when the payload looks like an {@code OrderRefundedEvent}
     * ({@code orderId} present but the {@code payMethod} field absent —
     * see the class javadoc for why field presence is the discriminator).
     */
    private boolean isOrderRefundedShape(byte[] data) {
        try {
            JsonNode node = mapper.readTree(data);
            return node != null && node.has("orderId") && !node.has("payMethod");
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
     * DTO for the {@code OrderPaidEvent} JSON payload
     * ({@code {"aggregateId":5001,"orderId":5001,"userId":9,"courseId":42,
     * "productType":"COURSE","payMethod":"MOCK","occurredAt":"…"}}).
     * {@code occurredAt} is kept as String to avoid coupling the consumer to a
     * JSR-310 mapper setup; it is logged verbatim in the audit entry.
     */
    record OrderPaidPayload(Long aggregateId, Long orderId, Long userId, Long courseId,
                            String productType, String payMethod, String occurredAt) {
    }
}
