package com.ep.grading.config;

import com.ep.disruptor.DisruptorPipeline;
import com.ep.disruptor.PublishResult;
import com.ep.disruptor.spring.DisruptorPipelineRegistry;
import com.ep.grading.common.DomainEvent;
import com.ep.grading.event.GradingEventPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * D-216: shared Disruptor multi-stage scheduling queue for grading-service —
 * the domain-event outbox pipeline ("grading-events").
 *
 * <p>Stage 1 {@code enqueue} (16384 slots, MULTI producer, BLOCKING wait —
 * matches assignment publish/close burst profile) → stage 2 {@code publish}
 * forwards each event to the {@link GradingEventPublisher} (Pulsar). This moves
 * the per-event Pulsar publish OUT of the JPA transaction (mirrors the audit
 * P2-8 fix in core-business: synchronous publishes inside the tx held the DB
 * connection for every event round-trip) and gains drop-on-full backpressure
 * instead of failing the transaction.
 *
 * <p>The grading-service domain events currently flow here:
 * <ul>
 *   <li>{@link com.ep.grading.homework.event.AssignmentPublishedEvent}</li>
 *   <li>{@link com.ep.grading.homework.event.AssignmentClosedEvent}</li>
 * </ul>
 *
 * <p>Default OFF ({@code ep.disruptor.enabled=false}) — the service keeps its
 * previous synchronous behaviour (publisher called directly post-commit), so
 * existing tests are unaffected; production enables the pipeline for batched,
 * back-pressured publishing.</p>
 *
 * <p>Lifecycle: the {@link DisruptorPipeline} bean is picked up by the shared
 * {@link DisruptorPipelineRegistry} (from ep-disruptor), which starts it during
 * SmartLifecycle ramp-up and stops it (stages drain in declaration order) on
 * shutdown. The {@link GradingEventPipeline} facade is what the service layer
 * calls to enqueue events; it degrades gracefully (no-op publish, logs a
 * warning) if the pipeline is absent.</p>
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
    public DisruptorPipeline gradingEventPipeline(GradingEventPublisher publisher) {
        // holder lets stage-1 forward into the pipeline being built
        final DisruptorPipeline[] ref = new DisruptorPipeline[1];

        DisruptorPipeline pipeline = DisruptorPipeline.named("grading-events")
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
        log.info("[DisruptorEventConfig] grading-events pipeline ready (enqueue → publish)");
        return pipeline;
    }

    /**
     * Facade the service layer uses to enqueue domain events onto the
     * grading-events pipeline. Loaded only when the pipeline is active
     * ({@code ep.disruptor.enabled=true}).
     */
    @Component
    @ConditionalOnProperty(name = "ep.disruptor.enabled", havingValue = "true")
    public static class GradingEventPipeline {

        private static final Logger log = LoggerFactory.getLogger(GradingEventPipeline.class);

        private final ObjectProvider<DisruptorPipeline> pipelineProvider;

        public GradingEventPipeline(ObjectProvider<DisruptorPipeline> pipelineProvider) {
            this.pipelineProvider = pipelineProvider;
        }

        /**
         * Enqueue one domain event. Returns the {@link PublishResult} from the
         * enqueue stage (PUBLISHED / DROPPED). Callers should treat DROPPED as
         * best-effort loss (the event was not published to Pulsar via the
         * pipeline) and rely on the synchronous fallback if they need
         * guaranteed delivery.
         *
         * <p>Safe to call before the pipeline bean has been started by the
         * registry — Disruptor {@code tryNext()} works once the RingBuffer
         * exists.</p>
         */
        public PublishResult enqueue(DomainEvent event) {
            if (event == null) {
                return PublishResult.DROPPED;
            }
            DisruptorPipeline pipeline = pipelineProvider.getIfAvailable();
            if (pipeline == null) {
                log.warn("[GradingEventPipeline] grading-events pipeline unavailable — event DROPPED: {}",
                        event.getClass().getSimpleName());
                return PublishResult.DROPPED;
            }
            EventSlot slot = new EventSlot();
            slot.event = event;
            return pipeline.publish("enqueue", slot);
        }
    }
}
