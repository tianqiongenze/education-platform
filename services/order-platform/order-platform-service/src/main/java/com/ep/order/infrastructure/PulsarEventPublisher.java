package com.ep.order.infrastructure;

import com.ep.order.event.DomainEvent;
import com.ep.web.common.event.EventSendRetrySupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes core-business Order domain events to Pulsar.
 * <p>
 * Best-effort contract: a publish failure (no client, producer create failure,
 * serialize failure, send failure) is caught and logged — it never propagates
 * to the caller and never rolls back the business transaction.
 * <p>
 * D-event-reliability: send failures are retried (bounded, linear backoff) via
 * {@link EventSendRetrySupport}; terminal failures are logged at WARN with the
 * structured envelope fields eventId/eventType/aggregateId/schemaVersion (the
 * failure handler there is the future transactional-outbox hook). Publish stays
 * post-commit only.
 * <p>
 * EP_DEV_ONLY guard: when {@code ep.pulsar.enabled=false} (the dev/test default)
 * the publisher is a no-op and never touches Pulsar. Tests therefore do not
 * need a running broker. When enabled, a {@link PulsarClient} bean must be
 * present; if it is missing the publisher still degrades to a no-op.
 * <p>
 * Simplified from {@code com.ep.business.event.PulsarEventPublisher}: the topic
 * is read directly via {@code @Value} rather than a dedicated properties class.
 * D-039: {@code producer} is volatile (visibility across threads, no
 * synchronized blocks).
 */
@Component
public class PulsarEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PulsarEventPublisher.class);

    private final ObjectProvider<PulsarClient> clientProvider;
    private final String topic;
    private final ObjectMapper mapper;
    private final boolean pulsarEnabled;
    private final int sendRetries;
    private final long sendBackoffMillis;

    private volatile Producer<byte[]> producer;

    public PulsarEventPublisher(ObjectProvider<PulsarClient> clientProvider,
                                @Value("${ep.pulsar.enabled:false}") boolean pulsarEnabled,
                                @Value("${ep.pulsar.topic.domain-events:domain-events}") String topic) {
        this(clientProvider, pulsarEnabled, topic,
                EventSendRetrySupport.DEFAULT_MAX_RETRIES, EventSendRetrySupport.DEFAULT_BACKOFF_MILLIS);
    }

    @Autowired
    public PulsarEventPublisher(ObjectProvider<PulsarClient> clientProvider,
                                @Value("${ep.pulsar.enabled:false}") boolean pulsarEnabled,
                                @Value("${ep.pulsar.topic.domain-events:domain-events}") String topic,
                                @Value("${ep.pulsar.send-retries:2}") int sendRetries,
                                @Value("${ep.pulsar.send-backoff-millis:200}") long sendBackoffMillis) {
        this.clientProvider = clientProvider;
        this.pulsarEnabled = pulsarEnabled;
        this.topic = topic;
        this.sendRetries = sendRetries;
        this.sendBackoffMillis = sendBackoffMillis;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void init() {
        if (!pulsarEnabled) {
            log.info("PulsarEventPublisher initialized in no-op mode (ep.pulsar.enabled=false)");
            return;
        }
        PulsarClient client = clientProvider.getIfAvailable();
        if (client == null) {
            log.warn("PulsarEventPublisher enabled but PulsarClient unavailable; degrading to no-op");
            return;
        }
        try {
            ProducerBuilder<byte[]> builder = client.newProducer()
                    .topic(topic)
                    .blockIfQueueFull(false)
                    .sendTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
            producer = builder.create();
            log.info("PulsarEventPublisher producer ready for topic={}", topic);
        } catch (Exception e) {
            log.warn("PulsarEventPublisher producer create failed for topic={}: {}", topic, e.getMessage());
        }
    }

    /**
     * Publish a single domain event. Fail-safe: never throws.
     */
    public void publish(DomainEvent event) {
        if (event == null) {
            return;
        }
        if (!pulsarEnabled || producer == null) {
            log.debug("Pulsar disabled/unavailable, skipping event: {}", event.getClass().getSimpleName());
            return;
        }
        try {
            byte[] payload = mapper.writeValueAsBytes(event);
            // D-event-reliability: bounded retries with backoff; on terminal
            // failure the shared-contract envelope fields are logged at WARN
            // (outbox hook lives in EventSendRetrySupport).
            EventSendRetrySupport.sendWithRetry(() -> producer.sendAsync(payload), event.envelope(),
                    sendRetries, sendBackoffMillis);
        } catch (Exception e) {
            log.warn("Pulsar publish failed for {}: {}", event.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * D-217: Publish a raw (already-serialized) outbox row to Pulsar.
     * <p>
     * Used by {@code OutboxRelayService} to forward outbox rows whose payload
     * was serialized at write time. Unlike {@link #publish(DomainEvent)} this
     * method <b>propagates</b> send failures (as a checked {@link Exception})
     * so the relay can record a retry and avoid losing the event: the outbox is
     * the source of truth, so a send failure must NOT be swallowed here. The
     * relay catches it, increments {@code retry_count}, and keeps the row
     * unpublished for the next tick.
     * <p>
     * EP_DEV_ONLY: when {@code ep.pulsar.enabled=false} (dev/test default) this
     * is a no-op that returns successfully, so the relay still marks the row
     * published — tests do not need a running broker.
     *
     * @param eventType the event type (used for logging and the retry envelope)
     * @param json      the JSON-serialized event payload
     * @throws Exception if the Pulsar send fails after all retries
     */
    public void publishRaw(String eventType, String json) throws Exception {
        if (eventType == null || json == null) {
            return;
        }
        if (!pulsarEnabled || producer == null) {
            log.debug("Pulsar disabled/unavailable, skipping outbox event: {}", eventType);
            return;
        }
        byte[] payload = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Build a minimal envelope for retry logging only; the eventId is not
        // recoverable from the raw payload, so use the eventType as a tag.
        com.ep.web.common.event.EventEnvelope env =
                com.ep.web.common.event.EventEnvelope.of(eventType, 0L, java.time.Instant.now());
        // sendWithRetry never throws and returns a future that completes false
        // on terminal failure — join and surface that as an exception so the
        // relay can count it as a retry.
        Boolean delivered = EventSendRetrySupport.sendWithRetry(
                () -> producer.sendAsync(payload), env, sendRetries, sendBackoffMillis).join();
        if (!Boolean.TRUE.equals(delivered)) {
            throw new IllegalStateException(
                    "D-217: Pulsar send failed for outbox event " + eventType + " after retries");
        }
    }
}
