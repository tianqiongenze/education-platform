package com.ep.order.infrastructure;

import com.ep.order.event.DomainEvent;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes SP-03 Order domain events to Pulsar.
 * <p>
 * Best-effort contract: a publish failure (no client, producer create failure,
 * serialize failure, send failure) is caught and logged — it never propagates
 * to the caller and never rolls back the business transaction.
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

    private volatile Producer<byte[]> producer;

    public PulsarEventPublisher(ObjectProvider<PulsarClient> clientProvider,
                                @Value("${ep.pulsar.enabled:false}") boolean pulsarEnabled,
                                @Value("${ep.pulsar.topic.domain-events:domain-events}") String topic) {
        this.clientProvider = clientProvider;
        this.pulsarEnabled = pulsarEnabled;
        this.topic = topic;
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
            producer.sendAsync(payload)
                    .exceptionally(e -> {
                        log.warn("Pulsar send failed for {}: {}", event.getClass().getSimpleName(), e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Pulsar publish failed for {}: {}", event.getClass().getSimpleName(), e.getMessage());
        }
    }
}
