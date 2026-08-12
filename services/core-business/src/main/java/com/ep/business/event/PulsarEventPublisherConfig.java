package com.ep.business.event;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link PulsarClientProperties} to the {@code ep.pulsar.*} namespace.
 */
@Configuration
@EnableConfigurationProperties(PulsarClientProperties.class)
public class PulsarEventPublisherConfig {
}
