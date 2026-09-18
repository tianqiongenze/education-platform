package com.ep.grading.config;

import com.ep.disruptor.DisruptorPipeline;
import com.ep.disruptor.DisruptorStats;
import com.ep.disruptor.PublishResult;
import com.ep.grading.common.DomainEvent;
import com.ep.grading.event.GradingEventPublisher;
import com.ep.grading.homework.event.AssignmentPublishedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-216: unit tests for the grading-events Disruptor pipeline.
 *
 * <p>Instantiates {@link DisruptorEventConfig} directly (the bean is
 * {@code @ConditionalOnProperty}, so we bypass Spring here) and drives real
 * events through the enqueue → publish stages, asserting that the
 * {@link GradingEventPublisher} receives each event.
 */
class DisruptorEventConfigTest {

    private DisruptorEventConfig config;
    private CapturingPublisher publisher;
    private DisruptorPipeline pipeline;

    @BeforeEach
    void setUp() {
        config = new DisruptorEventConfig();
        publisher = new CapturingPublisher();
        pipeline = config.gradingEventPipeline(publisher);
        // Start the pipeline manually — in Spring the DisruptorPipelineRegistry
        // (SmartLifecycle) would do this during context ramp-up.
        pipeline.start();
    }

    @AfterEach
    void tearDown() {
        if (pipeline != null) {
            pipeline.shutdown();
        }
    }

    @Test
    @DisplayName("pipeline is named grading-events with enqueue + publish stages")
    void pipelineHasCorrectNameAndStages() {
        assertEquals("grading-events", pipeline.name());
        List<String> stages = pipeline.stageNames();
        assertEquals(2, stages.size());
        assertEquals("enqueue", stages.get(0));
        assertEquals("publish", stages.get(1));
    }

    @Test
    @DisplayName("enqueued AssignmentPublishedEvent reaches the publisher")
    void enqueuedEventReachesPublisher() throws Exception {
        AssignmentPublishedEvent event = new AssignmentPublishedEvent(
                100L, 100L, 10L, 7L, Instant.now());

        DisruptorEventConfig.EventSlot slot = new DisruptorEventConfig.EventSlot();
        slot.event = event;
        PublishResult result = pipeline.publish("enqueue", slot);

        assertEquals(PublishResult.PUBLISHED, result);
        // Give the consumer thread time to drain enqueue → publish → publisher.
        Thread.sleep(200);
        assertEquals(1, publisher.received.size());
        assertTrue(publisher.received.get(0) instanceof AssignmentPublishedEvent);
    }

    @Test
    @DisplayName("publish stage stats reflect enqueued events")
    void publishStageStatsReflectEnqueuedEvents() throws Exception {
        for (int i = 0; i < 5; i++) {
            DisruptorEventConfig.EventSlot slot = new DisruptorEventConfig.EventSlot();
            slot.event = new AssignmentPublishedEvent((long) i, (long) i, 10L, 7L, Instant.now());
            pipeline.publish("enqueue", slot);
        }
        Thread.sleep(300);
        DisruptorStats enqueueStats = pipeline.stats("enqueue");
        DisruptorStats publishStats = pipeline.stats("publish");
        assertNotNull(enqueueStats);
        assertNotNull(publishStats);
        assertTrue(enqueueStats.published() >= 5, "enqueue stage should have published >= 5");
        assertTrue(publishStats.published() >= 5, "publish stage should have forwarded >= 5");
        assertEquals(5, publisher.received.size());
    }

    @Test
    @DisplayName("GradingEventPipeline facade enqueues through the pipeline")
    void facadeEnqueuesThroughPipeline() throws Exception {
        DisruptorEventConfig.GradingEventPipeline facade =
                new DisruptorEventConfig.GradingEventPipeline(singletonProvider(pipeline));

        AssignmentPublishedEvent event = new AssignmentPublishedEvent(
                200L, 200L, 20L, 8L, Instant.now());
        PublishResult result = facade.enqueue(event);

        assertEquals(PublishResult.PUBLISHED, result);
        Thread.sleep(200);
        assertEquals(1, publisher.received.size());
    }

    @Test
    @DisplayName("GradingEventPipeline facade returns DROPPED when pipeline absent")
    void facadeDropsWhenPipelineAbsent() {
        DisruptorEventConfig.GradingEventPipeline facade =
                new DisruptorEventConfig.GradingEventPipeline(emptyProvider());

        PublishResult result = facade.enqueue(
                new AssignmentPublishedEvent(1L, 1L, 1L, 1L, Instant.now()));

        assertEquals(PublishResult.DROPPED, result);
        assertEquals(0, publisher.received.size());
    }

    @Test
    @DisplayName("facade returns DROPPED for null event")
    void facadeDropsNullEvent() {
        DisruptorEventConfig.GradingEventPipeline facade =
                new DisruptorEventConfig.GradingEventPipeline(singletonProvider(pipeline));

        assertEquals(PublishResult.DROPPED, facade.enqueue(null));
    }

    /** A GradingEventPublisher that captures every published event for assertions. */
    static final class CapturingPublisher extends GradingEventPublisher {
        final List<DomainEvent> received = new ArrayList<>();

        CapturingPublisher() {
            super(null, new com.ep.grading.event.GradingPulsarProperties(), false);
        }

        @Override
        public void publish(DomainEvent event) {
            if (event != null) {
                received.add(event);
            }
        }
    }

    private static ObjectProvider<DisruptorPipeline> singletonProvider(DisruptorPipeline pipeline) {
        return new ObjectProvider<>() {
            @Override public DisruptorPipeline getObject(Object... args) { return pipeline; }
            @Override public DisruptorPipeline getObject() { return pipeline; }
            @Override public DisruptorPipeline getIfAvailable() { return pipeline; }
            @Override public DisruptorPipeline getIfUnique() { return pipeline; }
        };
    }

    private static ObjectProvider<DisruptorPipeline> emptyProvider() {
        return new ObjectProvider<>() {
            @Override public DisruptorPipeline getObject(Object... args) { throw new UnsupportedOperationException(); }
            @Override public DisruptorPipeline getObject() { throw new UnsupportedOperationException(); }
            @Override public DisruptorPipeline getIfAvailable() { return null; }
            @Override public DisruptorPipeline getIfUnique() { return null; }
        };
    }
}
