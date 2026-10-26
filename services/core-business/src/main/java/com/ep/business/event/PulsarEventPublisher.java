package com.ep.business.event;

import com.ep.business.common.DomainEvent;
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
 * D-066: publishes core-business domain events to Pulsar.
 * <p>
 * D-234 (v7 reliability upgrade): the publish path is bridged through the
 * generic transactional outbox (ep-web-common {@code com.ep.web.common.outbox})
 * — a publish first persists the event row to {@code ep_outbox} (durable,
 * retried by the background relay), then the relay forwards it to Pulsar. This
 * closes the dual-write gap the original best-effort contract left open: a
 * broker hiccup no longer loses a committed business event, it delays it.
 * <p>
 * When the outbox is not active ({@code ep.outbox.enabled=false} or no
 * {@code OutboxWriter} bean — e.g. thin unit-test contexts) the publisher
 * degrades to the historical direct-send behavior.
 * <p>
 * D-event-reliability: direct sends are retried (bounded, with linear backoff)
 * via {@link EventSendRetrySupport}; after the last attempt the event is logged
 * at WARN with the structured fields {@code eventId}/{@code eventType}/
 * {@code aggregateId}/{@code schemaVersion} (shared-contract envelope). Publish
 * stays post-commit only, never inside the business transaction.
 * <p>
 * EP_DEV_ONLY guard: when {@code ep.pulsar.enabled=false} (the dev/test default)
 * the direct-send path is a no-op; the relay's {@code publishRaw} follows the
 * same guard, so tests do not need a running broker. When enabled, the
 * {@link PulsarClient} bean (see {@link PulsarClientConfig}) is required; if it
 * is missing for any reason the publisher still degrades to a no-op rather
 * than throwing.
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
    /** D-234: optional outbox bridge — null when ep.outbox is disabled. */
    private final ObjectProvider<com.ep.web.common.outbox.OutboxWriter> outboxWriterProvider;

    private volatile Producer<byte[]> producer;

    public PulsarEventPublisher(ObjectProvider<PulsarClient> clientProvider,
                                PulsarClientProperties properties,
                                @Value("${ep.pulsar.enabled:false}") boolean pulsarEnabled,
                                ObjectProvider<com.ep.web.common.outbox.OutboxWriter> outboxWriterProvider) {
        this(clientProvider, properties, pulsarEnabled,
                EventSendRetrySupport.DEFAULT_MAX_RETRIES, EventSendRetrySupport.DEFAULT_BACKOFF_MILLIS,
                outboxWriterProvider);
    }

    /**
     * Legacy 3-arg constructor kept for existing tests: no outbox bridge.
     */
    public PulsarEventPublisher(ObjectProvider<PulsarClient> clientProvider,
                                PulsarClientProperties properties,
                                @Value("${ep.pulsar.enabled:false}") boolean pulsarEnabled) {
        this(clientProvider, properties, pulsarEnabled,
                EventSendRetrySupport.DEFAULT_MAX_RETRIES, EventSendRetrySupport.DEFAULT_BACKOFF_MILLIS, null);
    }

    /**
     * Legacy 5-arg constructor kept for existing tests: no outbox bridge.
     */
    public PulsarEventPublisher(ObjectProvider<PulsarClient> clientProvider,
                                PulsarClientProperties properties,
                                @Value("${ep.pulsar.enabled:false}") boolean pulsarEnabled,
                                @Value("${ep.pulsar.send-retries:2}") int sendRetries,
                                @Value("${ep.pulsar.send-backoff-millis:200}") long sendBackoffMillis) {
        this(clientProvider, properties, pulsarEnabled, sendRetries, sendBackoffMillis, null);
    }

    @Autowired
    public PulsarEventPublisher(ObjectProvider<PulsarClient> clientProvider,
                                PulsarClientProperties properties,
                                @Value("${ep.pulsar.enabled:false}") boolean pulsarEnabled,
                                @Value("${ep.pulsar.send-retries:2}") int sendRetries,
                                @Value("${ep.pulsar.send-backoff-millis:200}") long sendBackoffMillis,
                                ObjectProvider<com.ep.web.common.outbox.OutboxWriter> outboxWriterProvider) {
        this.clientProvider = clientProvider;
        this.topic = properties.getTopic().getDomainEvents();
        this.pulsarEnabled = pulsarEnabled;
        this.sendRetries = sendRetries;
        this.sendBackoffMillis = sendBackoffMillis;
        this.outboxWriterProvider = outboxWriterProvider;
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
                    .sendTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS);            producer = builder.create();
            log.info("PulsarEventPublisher producer ready for topic={}", topic);
        } catch (Exception e) {
            log.warn("PulsarEventPublisher producer create failed for topic={}: {}", topic, e.getMessage());
        }
    }

    /**
     * Publish a single domain event. Fail-safe: never throws.
     *
     * <p>D-234: when the outbox bridge is active the event is persisted to
     * {@code ep_outbox} (durable; the background relay forwards it with bounded
     * retries). Otherwise the historical direct-send path runs.</p>
     */
    public void publish(DomainEvent event) {
        if (event == null) {
            return;
        }
        // D-234 outbox bridge: persist-first, relay publishes later.
        com.ep.web.common.outbox.OutboxWriter outbox = outboxWriterProvider != null
                ? outboxWriterProvider.getIfAvailable() : null;
        if (outbox != null) {
            try {
                String aggregateId = event.envelope() != null && event.envelope().aggregateId() != null
                        ? event.envelope().aggregateId() : "unknown";
                // EventEnvelope carries no aggregate-type field; the event class
                // name doubles as the audit/routing dimension in ep_outbox.
                outbox.write(event.getClass().getSimpleName(), aggregateId, event.getClass().getSimpleName(),
                        mapper.writeValueAsString(event));
                return;
            } catch (Exception e) {
                // Outbox write failed (DB hiccup) — fall through to direct send
                // so the event still has a chance; the failure is logged loudly.
                log.warn("D-234: outbox write failed, falling back to direct send for {}: {}",
                        event.getClass().getSimpleName(), e.getMessage());
            }
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
     * D-234: raw publish used by the outbox relay. Same EP_DEV_ONLY guard as
     * {@link #publish} — a no-op when Pulsar is disabled so the relay marks
     * rows published and tests need no broker.
     */
    public void publishRaw(String eventType, String payloadJson) throws Exception {
        if (!pulsarEnabled || producer == null) {
            log.debug("Pulsar disabled/unavailable, relay skips raw publish: {}", eventType);
            return;
        }
        producer.send(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
