package com.ep.grading.config;

import com.ep.grading.event.GradingPulsarProperties;
import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D-216 wiring: builds a {@link PulsarClient} bean only when
 * {@code ep.pulsar.enabled=true}. In dev/test the flag defaults to false, so no
 * Pulsar connection is ever attempted and {@code GradingEventPublisher} degrades
 * to a no-op (best-effort publishing, EP_DEV_ONLY-safe).
 * <p>
 * The bean is created lazily via Spring's ObjectProvider so the publisher never
 * hard-fails if the client cannot be built.
 * <p>
 * Note: {@link GradingPulsarProperties} is enabled unconditionally by
 * {@link GradingEventPublisherConfig} so the publisher (always loaded) can read
 * its topic even when Pulsar itself is disabled.
 */
@Configuration
@ConditionalOnProperty(name = "ep.pulsar.enabled", havingValue = "true")
public class GradingPulsarClientConfig {

    private static final Logger log = LoggerFactory.getLogger(GradingPulsarClientConfig.class);

    @Bean(destroyMethod = "close")
    public PulsarClient pulsarClient(GradingPulsarProperties props) throws PulsarClientException {
        log.info("Creating PulsarClient for service-url={}", props.getServiceUrl());
        return PulsarClient.builder()
                .serviceUrl(props.getServiceUrl())
                .build();
    }

    @PreDestroy
    public void shutdown() {
        log.info("GradingPulsarClientConfig shutting down");
    }
}
