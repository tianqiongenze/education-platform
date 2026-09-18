package com.ep.business.config;

import com.ep.business.common.DomainEvent;
import com.ep.business.event.PulsarEventPublisher;
import com.ep.disruptor.DisruptorPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * D-216: shared Disruptor multi-stage scheduling queue for core-business —
 * the domain-event outbox pipeline.
 *
 * <p>Stage 1 {@code enqueue} (16384 slots, MULTI producer, BLOCKING wait —
 * matches tx-commit burst profile) → stage 2 {@code publish} forwards each
 * event to the Pulsar publisher. This moves the per-event Pulsar publish OUT
 * of the JPA transaction (audit P2-8: one-by-one synchronous publishes inside
 * the tx held the DB connection for every event round-trip) and gains
 * drop-on-full backpressure instead of failing the transaction.</p>
 *
 * <p>Default OFF ({@code ep.disruptor.enabled=false}) — the service keeps its
 * previous synchronous behaviour, so existing tests are unaffected; production
 * enables the pipeline for batched, back-pressured publishing.</p>
 */
@Configuration
@ConditionalOnProperty(name = "ep.disruptor.enabled", havingValue = "true")
public class DisruptorEventConfig {

    private static final Logger log = LoggerFactory.getLogger(DisruptorEventConfig.class);

    /** Disruptor slot: holds one domain event while it travels the pipeline. */
    public static final class EventSlot {
        public DomainEvent event;
    }

    @Bean
    public DisruptorPipeline domainEventPipeline(PulsarEventPublisher publisher) {
        // holder lets stage-1 forward into the pipeline being built
        final DisruptorPipeline[] ref = new DisruptorPipeline[1];

        DisruptorPipeline pipeline = DisruptorPipeline.named("core-business-events")
                .<EventSlot>stage("enqueue", EventSlot::new,
                        (slot, seq, src) -> slot.event = src.event,
                        (slot, seq, eob) -> ref[0].forward("enqueue", slot))
                .stage("publish", EventSlot::new,
                        (slot, seq, src) -> slot.event = src.event,
                        (slot, seq, eob) -> {
                            if (slot.event != null) {
                                publisher.publish(slot.event);
                            }
                        });
        ref[0] = pipeline;
        log.info("[DisruptorEventConfig] core-business-events pipeline ready (enqueue → publish)");
        return pipeline;
    }
}
