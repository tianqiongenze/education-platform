package com.ep.order.config;

import com.ep.disruptor.DisruptorPipeline;
import com.ep.order.event.DomainEvent;
import com.ep.order.infrastructure.PulsarEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D-216: shared Disruptor multi-stage scheduling queue for order-platform-service
 * — the order domain-event outbox pipeline.
 *
 * <p>Stage 1 {@code enqueue} (16384 slots, MULTI producer, BLOCKING wait —
 * matches tx-commit burst profile) captures each {@link DomainEvent} recorded by
 * the {@code OrderModel} aggregate after the JPA transaction commits, and stage
 * 2 {@code publish} forwards each event to {@link PulsarEventPublisher}. This
 * moves the per-event Pulsar publish OUT of the JPA transaction (the legacy
 * synchronous publishes inside the tx held the DB connection for every event
 * round-trip) and gains drop-on-full backpressure instead of failing the
 * transaction.</p>
 *
 * <p>Default OFF ({@code ep.disruptor.enabled=false}) — the service keeps its
 * previous synchronous behaviour, so existing tests are unaffected; production
 * enables the pipeline for batched, back-pressured publishing.</p>
 *
 * <p>Mirrors {@code com.ep.business.config.DisruptorEventConfig} from
 * core-business so the order middle platform shares the same outbox pattern and
 * lifecycle (the pipeline bean is picked up by
 * {@code DisruptorPipelineRegistry}).</p>
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
    public DisruptorPipeline orderEventPipeline(PulsarEventPublisher publisher) {
        // holder lets stage-1 forward into the pipeline being built
        final DisruptorPipeline[] ref = new DisruptorPipeline[1];

        DisruptorPipeline pipeline = DisruptorPipeline.named("order-events")
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
        log.info("[DisruptorEventConfig] order-events pipeline ready (enqueue → publish)");
        return pipeline;
    }
}
