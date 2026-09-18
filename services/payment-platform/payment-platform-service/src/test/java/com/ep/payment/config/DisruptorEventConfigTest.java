package com.ep.payment.config;

import com.ep.disruptor.DisruptorPipeline;
import com.ep.disruptor.PublishResult;
import com.ep.payment.event.PaymentEvent;
import com.ep.payment.event.PaymentResultEvent;
import com.ep.payment.infrastructure.PaymentEventPublisher;
import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
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
    private PaymentEventPublisher publisher;
    private DisruptorPipeline pipeline;
    private final List<PaymentEvent> published = new ArrayList<>();

    @BeforeEach
    void setUp() {
        config = new DisruptorEventConfig();
        publisher = mock(PaymentEventPublisher.class);
        published.clear();
        ArgumentCaptor<PaymentEvent> captor = ArgumentCaptor.forClass(PaymentEvent.class);
        doNothing().when(publisher).publish(captor.capture());
        // record publishes via a side-effect on the mock
        org.mockito.Mockito.doAnswer(inv -> {
            published.add(inv.getArgument(0));
            return null;
        }).when(publisher).publish(org.mockito.ArgumentMatchers.any(PaymentEvent.class));

        pipeline = config.paymentEventPipeline(publisher);
        pipeline.start();
    }

    @AfterEach
    void tearDown() {
        if (pipeline != null) {
            pipeline.shutdown();
        }
    }

    @Test
    @DisplayName("pipeline is named payment-events with enqueue + publish stages")
    void pipelineShape() {
        assertEquals("payment-events", pipeline.name());
        List<String> stages = pipeline.stageNames();
        assertEquals(2, stages.size());
        assertEquals("enqueue", stages.get(0));
        assertEquals("publish", stages.get(1));
    }

    @Test
    @DisplayName("enqueue → publish forwards a SUCCESS PaymentResultEvent to the publisher")
    void publishesSuccessEvent() throws Exception {
        PaymentResultEvent evt = makeEvent("pay-1", "order-1", PaymentStatus.SUCCESS);

        PublishResult result = publishEnqueue(evt);
        assertSame(PublishResult.PUBLISHED, result);
        waitForPublish(1);

        assertEquals(1, published.size());
        PaymentResultEvent out = (PaymentResultEvent) published.get(0);
        assertEquals("pay-1", out.paymentId());
        assertEquals("order-1", out.orderId());
        assertEquals(PaymentStatus.SUCCESS, out.status());
    }

    @Test
    @DisplayName("enqueue → publish forwards a FAILED PaymentResultEvent to the publisher")
    void publishesFailedEvent() throws Exception {
        PaymentResultEvent evt = makeEvent("pay-2", "order-2", PaymentStatus.FAILED);

        publishEnqueue(evt);
        waitForPublish(1);

        assertEquals(1, published.size());
        assertEquals(PaymentStatus.FAILED, ((PaymentResultEvent) published.get(0)).status());
    }

    @Test
    @DisplayName("enqueue → publish forwards a REFUNDED PaymentResultEvent to the publisher")
    void publishesRefundedEvent() throws Exception {
        PaymentResultEvent evt = makeEvent("pay-3", "order-3", PaymentStatus.REFUNDED);

        publishEnqueue(evt);
        waitForPublish(1);

        assertEquals(1, published.size());
        assertEquals(PaymentStatus.REFUNDED, ((PaymentResultEvent) published.get(0)).status());
    }

    @Test
    @DisplayName("multiple events are published in order")
    void publishesMultipleEvents() throws Exception {
        publishEnqueue(makeEvent("pay-a", "order-a", PaymentStatus.SUCCESS));
        publishEnqueue(makeEvent("pay-b", "order-b", PaymentStatus.REFUNDED));
        waitForPublish(2);

        assertEquals(2, published.size());
        assertEquals("pay-a", ((PaymentResultEvent) published.get(0)).paymentId());
        assertEquals("pay-b", ((PaymentResultEvent) published.get(1)).paymentId());
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

    private PublishResult publishEnqueue(PaymentEvent evt) {
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

    private static PaymentResultEvent makeEvent(String paymentId, String orderId, PaymentStatus status) {
        return new PaymentResultEvent(paymentId, paymentId, orderId, "tenant-1",
                PaymentChannel.MOCK, status, new BigDecimal("99.00"), "CNY",
                "channel-tx-" + paymentId, Instant.now());
    }
}
