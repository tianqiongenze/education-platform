package com.ep.grading.event;

import com.ep.grading.common.DomainEvent;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * D-216: publishes grading-service domain events to Pulsar.
 * <p>
 * Mirrors {@code com.ep.business.event.PulsarEventPublisher} from core-business:
 * best-effort contract — a publish failure (no client, producer create failure,
 * serialize failure, send failure) is caught and logged and never propagates to
 * the caller or rolls back the business transaction.
 * <p>
 * D-event-reliability: send failures are retried (bounded, linear backoff) via
 * {@link EventSendRetrySupport}; terminal failures are logged at WARN with the
 * structured envelope fields eventId/eventType/aggregateId/schemaVersion (the
 * failure handler there is the future transactional-outbox hook). Publish stays
 * post-commit only.
 * <p>
 * EP_DEV_ONLY guard: when {@code ep.pulsar.enabled=false} (the dev/test default)
 * the publisher is a no-op and never touches Pulsar, so tests do not need a
 * running broker. The {@link PulsarClient} bean (created by
 * {@link PulsarClientConfig}) is required only when enabled; if it is missing
 * for any reason the publisher still degrades to a no-op rather than throwing.
 *
 * <p>Consumed by the {@code grading-events} Disruptor pipeline publish stage
 * (see {@code DisruptorEventConfig}); can also be called directly when the
 * pipeline is disabled ({@code ep.disruptor.enabled=false}).
 */
@Component
public class GradingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(GradingEventPublisher.class);

    private final ObjectProvider<PulsarClient> clientProvider;
    private final String topic;
    private final ObjectMapper mapper;
    private final boolean pulsarEnabled;
    private final int sendRetries;
    private final long sendBackoffMillis;

    private volatile Producer<byte[]> producer;

    public GradingEventPublisher(ObjectProvider<PulsarClient> clientProvider,
                                 GradingPulsarProperties properties,
                                 @Value("${ep.pulsar.enabled:false}") boolean pulsarEnabled) {
        this(clientProvider, properties, pulsarEnabled,
                EventSendRetrySupport.DEFAULT_MAX_RETRIES, EventSendRetrySupport.DEFAULT_BACKOFF_MILLIS, null);
    }

    /**
     * Legacy 5-arg constructor kept for existing tests: no outbox bridge.
     */
    public GradingEventPublisher(ObjectProvider<PulsarClient> clientProvider,
                                 GradingPulsarProperties properties,
                                 @Value("${ep.pulsar.enabled:false}") boolean pulsarEnabled,
                                 @Value("${ep.pulsar.send-retries:2}") int sendRetries,
                                 @Value("${ep.pulsar.send-backoff-millis:200}") long sendBackoffMillis) {
        this(clientProvider, properties, pulsarEnabled, sendRetries, sendBackoffMillis, null);
    }

    @Autowired
    public GradingEventPublisher(ObjectProvider<PulsarClient> clientProvider,
                                 GradingPulsarProperties properties,
                                 @Value("${ep.pulsar.enabled:false}") boolean pulsarEnabled,
                                 @Value("${ep.pulsar.send-retries:2}") int sendRetries,
                                 @Value("${ep.pulsar.send-backoff-millis:200}") long sendBackoffMillis,
                                 org.springframework.beans.factory.ObjectProvider<com.ep.web.common.outbox.OutboxWriter> outboxWriterProvider) {
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

    /** D-234: optional outbox bridge — null when ep.outbox is disabled. */
    private final org.springframework.beans.factory.ObjectProvider<com.ep.web.common.outbox.OutboxWriter> outboxWriterProvider;

    @PostConstruct
    public void init() {
        if (!pulsarEnabled) {
            log.info("GradingEventPublisher initialized in no-op mode (ep.pulsar.enabled=false)");
            return;
        }
        PulsarClient client = clientProvider.getIfAvailable();
        if (client == null) {
            log.warn("GradingEventPublisher enabled but PulsarClient unavailable; degrading to no-op");
            return;
        }
        try {
            ProducerBuilder<byte[]> builder = client.newProducer()
                    .topic(topic)
                    .blockIfQueueFull(false)
                    .sendTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
            producer = builder.create();
            log.info("GradingEventPublisher producer ready for topic={}", topic);
        } catch (Exception e) {
            log.warn("GradingEventPublisher producer create failed for topic={}: {}", topic, e.getMessage());
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
                outbox.write(event.getClass().getSimpleName(), aggregateId, event.getClass().getSimpleName(),
                        mapper.writeValueAsString(event));
                return;
            } catch (Exception e) {
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
            log.warn("Pulsar publish failed for {}: {}",
                    event.getClass().getSimpleName(), e.getMessage());
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
