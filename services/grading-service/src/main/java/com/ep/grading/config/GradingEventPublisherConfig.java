package com.ep.grading.config;

import com.ep.grading.event.GradingPulsarProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link GradingPulsarProperties} to the {@code ep.pulsar.*} namespace.
 * Loaded unconditionally so {@code GradingEventPublisher} (always present) can
 * read its topic even when Pulsar itself is disabled.
 */
@Configuration
@EnableConfigurationProperties(GradingPulsarProperties.class)
public class GradingEventPublisherConfig {
}
