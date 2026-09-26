package com.ep.bff.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderPaidConsumer tests: pure JUnit + Mockito (no Spring context, no
 * broker — same pattern as {@link CoursePublishedConsumerTest}). Feeds JSON
 * through the package-private {@code handleMessage} entry point with a mocked
 * Pulsar {@link Consumer}/{@link Message} and verifies the
 * {@link OrderWarmupClient} port, the AUDIT entry (via a logback
 * {@link ListAppender} on the {@code AUDIT} logger) and poison-pill-safe
 * acknowledgement.
 */
class OrderPaidConsumerTest {

    private OrderWarmupClient orderWarmupClient;
    private OrderPaidConsumer consumer;
    private Consumer<byte[]> pulsarConsumer;
    private ListAppender<ILoggingEvent> auditAppender;
    private Logger auditLogger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orderWarmupClient = mock(OrderWarmupClient.class);
        ObjectProvider<org.apache.pulsar.client.api.PulsarClient> clientProvider = mock(ObjectProvider.class);
        consumer = new OrderPaidConsumer(clientProvider, orderWarmupClient,
                "persistent://public/default/order-platform-events", "test-subscription");
        pulsarConsumer = mock(Consumer.class);

        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);
    }

    @AfterEach
    void detachAppender() {
        auditLogger.detachAppender(auditAppender);
    }

    @SuppressWarnings("unchecked")
    private Message<byte[]> messageWithJson(String json) {
        Message<byte[]> msg = mock(Message.class);
        when(msg.getData()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        return msg;
    }

    @Test
    void paidPayloadWritesAuditAndWarmsOrderDetail() {
        String payload = "{\"aggregateId\":5001,\"orderId\":5001,\"userId\":9,\"courseId\":42,"
                + "\"productType\":\"COURSE\",\"payMethod\":\"MOCK\","
                + "\"occurredAt\":\"2026-09-01T10:15:30Z\"}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        assertEquals(1, auditAppender.list.size());
        assertEquals(Level.INFO, auditAppender.list.get(0).getLevel());
        String json = auditAppender.list.get(0).getFormattedMessage();
        assertTrue(json.contains("\"action\":\"ORDER_PAID\""), json);
        assertTrue(json.contains("\"orderId\":5001"), json);
        assertTrue(json.contains("\"userId\":9"), json);
        assertTrue(json.contains("\"courseId\":42"), json);
        assertTrue(json.contains("\"productType\":\"COURSE\""), json);
        verify(orderWarmupClient).warmupOrderDetail(5001L);
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void payloadWithExtraFieldsIsTolerated() {
        String payload = "{\"aggregateId\":5001,\"orderId\":5002,\"userId\":9,\"courseId\":42,"
                + "\"productType\":\"COURSE\",\"payMethod\":null,"
                + "\"occurredAt\":\"2026-09-01T10:15:30Z\",\"futureField\":\"x\"}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        // payMethod:null (field present, value null) is still an OrderPaid shape.
        verify(orderWarmupClient).warmupOrderDetail(5002L);
        assertEquals(1, auditAppender.list.size());
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void orderRefundedShapeIsSkippedButAcknowledged() {
        // OrderRefundedEvent shares the order topic and has no payMethod field —
        // not an ORDER_PAID event, skip without audit/warm-up.
        String payload = "{\"aggregateId\":5001,\"orderId\":5001,\"userId\":9,\"courseId\":42,"
                + "\"productType\":\"COURSE\",\"occurredAt\":\"2026-09-01T10:15:30Z\"}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        verify(orderWarmupClient, never()).warmupOrderDetail(anyLong());
        assertEquals(0, auditAppender.list.size());
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void payloadWithoutOrderIdIsSkippedButAcknowledged() {
        // Missing-field tolerance: incomplete payload -> skip (WARN), never crash.
        String payload = "{\"aggregateId\":1,\"occurredAt\":\"2026-09-01T10:15:30Z\"}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        verify(orderWarmupClient, never()).warmupOrderDetail(anyLong());
        assertEquals(0, auditAppender.list.size());
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void invalidJsonIsSkippedButStillAcknowledged() {
        // Poison pill: never blocks the subscription.
        consumer.handleMessage(pulsarConsumer, messageWithJson("not-json{"));

        verify(orderWarmupClient, never()).warmupOrderDetail(anyLong());
        assertEquals(0, auditAppender.list.size());
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void nullMessageDataIsAcknowledgedWithoutSideEffects() {
        @SuppressWarnings("unchecked")
        Message<byte[]> msg = mock(Message.class);
        when(msg.getData()).thenReturn(null);

        consumer.handleMessage(pulsarConsumer, msg);

        verify(orderWarmupClient, never()).warmupOrderDetail(anyLong());
        assertEquals(0, auditAppender.list.size());
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void warmupFailureDoesNotBlockAcknowledgementAndAuditStillWritten() {
        String payload = "{\"aggregateId\":5001,\"orderId\":5001,\"userId\":9,\"courseId\":42,"
                + "\"productType\":\"COURSE\",\"payMethod\":\"MOCK\","
                + "\"occurredAt\":\"2026-09-01T10:15:30Z\"}";
        doThrow(new RuntimeException("order-platform down"))
                .when(orderWarmupClient).warmupOrderDetail(5001L);

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        // Audit was written BEFORE the warm-up call, so it survives the failure.
        assertEquals(1, auditAppender.list.size());
        verify(orderWarmupClient).warmupOrderDetail(5001L);
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }
}
