package com.ep.business.event;

import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D-066 wiring: builds a {@link PulsarClient} bean only when
 * {@code ep.pulsar.enabled=true}. In dev/test the flag defaults to false, so no
 * Pulsar connection is ever attempted and {@link PulsarEventPublisher} degrades
 * to a no-op (best-effort publishing, EP_DEV_ONLY-safe).
 * <p>
 * The bean is created lazily via {@link ObjectProvider} so the publisher never
 * hard-fails if the client cannot be built.
 */
@Configuration
@ConditionalOnProperty(name = "ep.pulsar.enabled", havingValue = "true")
public class PulsarClientConfig {

    private static final Logger log = LoggerFactory.getLogger(PulsarClientConfig.class);

    @Bean(destroyMethod = "close")
    public PulsarClient pulsarClient(PulsarClientProperties props) throws PulsarClientException {
        log.info("Creating PulsarClient for service-url={}", props.getServiceUrl());
        return PulsarClient.builder()
                .serviceUrl(props.getServiceUrl())
                .build();
    }

    @PreDestroy
    public void shutdown() {
        log.info("PulsarClientConfig shutting down");
    }
}
