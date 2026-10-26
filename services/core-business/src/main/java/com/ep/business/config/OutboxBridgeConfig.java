package com.ep.business.config;

import com.ep.business.event.PulsarEventPublisher;
import com.ep.web.common.outbox.OutboxPublisherPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D-234: wires the generic transactional outbox (ep-web-common) to this
 * service's Pulsar publisher.
 *
 * <p>The bridge flow: {@code PulsarEventPublisher.publish(event)} persists the
 * event row to {@code ep_outbox} → the ep-web-common {@code OutboxRelay} drains
 * it on its own daemon scheduler → this port forwards the raw payload to
 * Pulsar (with the same EP_DEV_ONLY guard as the direct path).</p>
 *
 * <p>Hosts enable the outbox via {@code ep.outbox.enabled=true} in
 * application.yml — the default here follows the reliability-first posture
 * for a core business domain whose events drive downstream grading and
 * notification flows.</p>
 */
@Configuration
public class OutboxBridgeConfig {

    @Bean
    public OutboxPublisherPort coreBusinessOutboxPublisher(PulsarEventPublisher publisher) {
        return publisher::publishRaw;
    }
}
