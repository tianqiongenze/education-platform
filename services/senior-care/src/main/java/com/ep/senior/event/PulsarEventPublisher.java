package com.ep.senior.event;

import com.ep.senior.common.DomainEvent;
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
 * D-147: publishes senior-care domain events to Pulsar.
 * Best-effort: a publish failure is logged and never propagates.
 * EP_DEV_ONLY guard: when ep.pulsar.enabled=false (dev/test default),
 * the publisher is a no-op.
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
                                PulsarClientProperties properties,
                                @Value("${ep.pulsar.enabled:false}") boolean pulsarEnabled) {
        this.clientProvider = clientProvider;
        this.topic = properties.getTopic().getDomainEvents();
        this.pulsarEnabled = pulsarEnabled;
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

    public void publish(DomainEvent event) {
        if (event == null) return;
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
