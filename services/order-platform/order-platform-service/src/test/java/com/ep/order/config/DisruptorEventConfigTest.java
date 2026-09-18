package com.ep.order.config;

import com.ep.disruptor.DisruptorPipeline;
import com.ep.disruptor.PublishResult;
import com.ep.order.event.DomainEvent;
import com.ep.order.event.OrderPaidEvent;
import com.ep.order.event.OrderRefundedEvent;
import com.ep.order.infrastructure.PulsarEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link DisruptorEventConfig}. The pipeline is built directly
 * (the @ConditionalOnProperty gate is only relevant for Spring context boot)
 * so we can verify stage wiring and end-to-end event forwarding without a
 * running Pulsar broker.
 */
class DisruptorEventConfigTest {

    private DisruptorEventConfig config;
    private PulsarEventPublisher publisher;
    private DisruptorPipeline pipeline;
    private final List<DomainEvent> published = new ArrayList<>();

    @BeforeEach
    void setUp() {
        config = new DisruptorEventConfig();
        publisher = mock(PulsarEventPublisher.class);
        // capture every event the publish stage forwards to the publisher
        published.clear();
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        doNothing().when(publisher).publish(captor.capture());
        doNothing().when(publisher).publish(org.mockito.ArgumentMatchers.any());
        // record publishes via a side-effect on the mock
        org.mockito.Mockito.doAnswer(inv -> {
            published.add(inv.getArgument(0));
            return null;
        }).when(publisher).publish(org.mockito.ArgumentMatchers.any(DomainEvent.class));

        pipeline = config.orderEventPipeline(publisher);
        pipeline.start();
    }

    @AfterEach
    void tearDown() {
        if (pipeline != null) {
            pipeline.shutdown();
        }
    }

    @Test
    @DisplayName("pipeline is named order-events with enqueue + publish stages")
    void pipelineShape() {
        assertEquals("order-events", pipeline.name());
        List<String> stages = pipeline.stageNames();
        assertEquals(2, stages.size());
        assertEquals("enqueue", stages.get(0));
        assertEquals("publish", stages.get(1));
    }

    @Test
    @DisplayName("enqueue → publish forwards an OrderPaidEvent to the Pulsar publisher")
    void publishesPaidEvent() throws Exception {
        OrderPaidEvent evt = new OrderPaidEvent(101L, 101L, 7L, 42L, "COURSE", "ALIPAY", Instant.now());

        PublishResult result = publishEnqueue(evt);
        assertSame(PublishResult.PUBLISHED, result);
        waitForPublish(1);

        assertEquals(1, published.size());
        assertTrue(published.get(0) instanceof OrderPaidEvent);
        assertEquals(101L, ((OrderPaidEvent) published.get(0)).orderId());
    }

    @Test
    @DisplayName("enqueue → publish forwards an OrderRefundedEvent to the Pulsar publisher")
    void publishesRefundedEvent() throws Exception {
        OrderRefundedEvent evt = new OrderRefundedEvent(202L, 202L, 8L, 13L, "MEMBERSHIP", Instant.now());

        PublishResult result = publishEnqueue(evt);
        assertSame(PublishResult.PUBLISHED, result);
        waitForPublish(1);

        assertEquals(1, published.size());
        assertTrue(published.get(0) instanceof OrderRefundedEvent);
        assertEquals(202L, ((OrderRefundedEvent) published.get(0)).orderId());
    }

    @Test
    @DisplayName("multiple events are published in order")
    void publishesMultipleEvents() throws Exception {
        OrderPaidEvent e1 = new OrderPaidEvent(1L, 1L, 1L, 1L, "COURSE", "ALIPAY", Instant.now());
        OrderRefundedEvent e2 = new OrderRefundedEvent(2L, 2L, 2L, 2L, "MEMBERSHIP", Instant.now());

        publishEnqueue(e1);
        publishEnqueue(e2);
        waitForPublish(2);

        assertEquals(2, published.size());
        assertTrue(published.get(0) instanceof OrderPaidEvent);
        assertTrue(published.get(1) instanceof OrderRefundedEvent);
    }

    @Test
    @DisplayName("stage stats are exposed for both stages")
    void statsExposed() {
        assertNotNull(pipeline.stats("enqueue"));
        assertNotNull(pipeline.stats("publish"));
        assertNotNull(pipeline.statsAll());
        assertEquals(2, pipeline.statsAll().size());
    }

    // ---------- helpers ----------

    private PublishResult publishEnqueue(DomainEvent evt) {
        DisruptorEventConfig.EventSlot slot = new DisruptorEventConfig.EventSlot();
        slot.event = evt;
        return pipeline.publish("enqueue", slot);
    }

    /** Block until the publish stage has emitted at least {@code n} events, or timeout. */
    private void waitForPublish(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (published.size() < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(published.size() >= n,
                "expected at least " + n + " publishes, got " + published.size());
    }
}
