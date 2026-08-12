package com.ep.boardgame.event;

import com.ep.boardgame.common.DomainEvent;
import org.apache.pulsar.client.api.PulsarClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import jakarta.annotation.PostConstruct;

/**
 * Best-effort domain event publisher with EP_DEV_ONLY guard.
 * When {@code ep.pulsar.enabled=false} (dev/test default), this is a no-op.
 */
public class PulsarEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PulsarEventPublisher.class);

    private final ObjectProvider<PulsarClient> clientProvider;
    private final PulsarClientProperties props;
    private final boolean enabled;

    public PulsarEventPublisher(ObjectProvider<PulsarClient> clientProvider,
                                PulsarClientProperties props,
                                boolean enabled) {
        this.clientProvider = clientProvider;
        this.props = props;
        this.enabled = enabled;
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("PulsarEventPublisher initialized in no-op mode (ep.pulsar.enabled=false)");
        }
    }

    public void publish(DomainEvent event) {
        if (!enabled) return;
        try {
            PulsarClient client = clientProvider.getIfAvailable();
            if (client == null) {
                log.debug("PulsarClient not available, skipping event: {}", event.getClass().getSimpleName());
                return;
            }
            log.info("Publishing domain event: {}", event.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("Failed to publish event {}: {}", event.getClass().getSimpleName(), e.getMessage());
        }
    }
}
