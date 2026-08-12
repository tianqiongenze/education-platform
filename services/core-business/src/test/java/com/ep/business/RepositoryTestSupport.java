package com.ep.business;

import com.ep.business.event.PulsarClientProperties;
import com.ep.business.event.PulsarEventPublisher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * D-132: test-only beans for the {@code @DataJpaTest} slice tests
 * ({@code CourseRepositoryTest}, {@code UserRepositoryTest}).
 * <p>
 * Those slice tests bootstrap the full {@code BusinessApplication} context (so
 * {@code @DubboService} beans like {@code CourseServiceImpl} are instantiated),
 * but the JPA slice does not register the event-infrastructure beans
 * ({@link PulsarEventPublisher}, {@link PulsarClientProperties}) those services
 * depend on. Rather than load a real Pulsar client, this config supplies a no-op
 * publisher (with {@code ep.pulsar.enabled=false}) so the context wires and the
 * repository layer is exercised in isolation.
 * <p>
 * EP_DEV_ONLY-safe: never touches a broker.
 */
@TestConfiguration
public class RepositoryTestSupport {

    @Bean
    PulsarClientProperties pulsarClientProperties() {
        return new PulsarClientProperties();
    }

    @Bean
    PulsarEventPublisher pulsarEventPublisher(PulsarClientProperties properties) {
        // pulsarEnabled=false → the publisher never touches Pulsar (no-op on init + publish).
        return new PulsarEventPublisher(null, properties, false);
    }
}
