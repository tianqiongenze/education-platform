package com.ep.grading.config;

import com.ep.grading.event.GradingEventPublisher;
import com.ep.web.common.outbox.OutboxPublisherPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D-234: wires the generic transactional outbox (ep-web-common) to this
 * service's grading Pulsar publisher.
 *
 * <p>The bridge flow: {@code GradingEventPublisher.publish(event)} persists the
 * event row to {@code ep_outbox} → the ep-web-common {@code OutboxRelay} drains
 * it on its own daemon scheduler → this port forwards the raw payload to
 * Pulsar (with the same EP_DEV_ONLY guard as the direct path).</p>
 */
@Configuration
public class OutboxBridgeConfig {

    @Bean
    public OutboxPublisherPort gradingOutboxPublisher(GradingEventPublisher publisher) {
        return publisher::publishRaw;
    }
}
