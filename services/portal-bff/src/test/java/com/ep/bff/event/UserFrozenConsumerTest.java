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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserFrozenConsumer tests: pure JUnit + Mockito (no Spring context, no
 * broker — same pattern as {@link CoursePublishedConsumerTest}). Feeds JSON
 * through the package-private {@code handleMessage} entry point with a mocked
 * Pulsar {@link Consumer}/{@link Message}. AUDIT side effects are verified by
 * attaching a logback {@link ListAppender} to the {@code AUDIT} logger (the
 * seam {@link EventAuditLogger} writes to).
 */
class UserFrozenConsumerTest {

    private UserFrozenConsumer consumer;
    private Consumer<byte[]> pulsarConsumer;
    private ListAppender<ILoggingEvent> auditAppender;
    private Logger auditLogger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<org.apache.pulsar.client.api.PulsarClient> clientProvider = mock(ObjectProvider.class);
        consumer = new UserFrozenConsumer(clientProvider,
                "persistent://public/default/ep-business-domain-events", "test-subscription");
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
    void frozenPayloadWritesStructuredAuditEntry() {
        String payload = "{\"aggregateId\":9,\"userId\":9,\"reason\":\"fraud suspicion\","
                + "\"occurredAt\":\"2026-09-01T10:15:30Z\"}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        assertEquals(1, auditAppender.list.size());
        ILoggingEvent event = auditAppender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        String json = event.getFormattedMessage();
        assertTrue(json.contains("\"action\":\"USER_FROZEN\""), json);
        assertTrue(json.contains("\"userId\":9"), json);
        assertTrue(json.contains("\"reason\":\"fraud suspicion\""), json);
        assertTrue(json.contains("\"occurredAt\":\"2026-09-01T10:15:30Z\""), json);
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void payloadMissingReasonStillWritesAuditWithNullReason() {
        // Missing non-critical field: tolerated, audit still written, no crash.
        String payload = "{\"aggregateId\":9,\"userId\":11,\"occurredAt\":\"2026-09-01T10:15:30Z\"}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        assertEquals(1, auditAppender.list.size());
        assertTrue(auditAppender.list.get(0).getFormattedMessage().contains("\"reason\":null"));
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void payloadWithExtraFieldsIsTolerated() {
        String payload = "{\"aggregateId\":9,\"userId\":12,\"reason\":\"abuse\","
                + "\"occurredAt\":\"2026-09-01T10:15:30Z\",\"futureField\":\"x\"}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        assertEquals(1, auditAppender.list.size());
        assertTrue(auditAppender.list.get(0).getFormattedMessage().contains("\"userId\":12"));
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void courseShapePayloadIsSkippedButAcknowledged() {
        // CoursePublished/CourseArchived share this topic and carry no userId —
        // expected co-tenant traffic, no audit entry.
        String payload = "{\"aggregateId\":42,\"courseId\":42,\"teacherId\":7,"
                + "\"occurredAt\":\"2026-09-01T10:15:30Z\"}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        assertEquals(0, auditAppender.list.size());
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void payloadWithoutUserIdIsSkippedButAcknowledged() {
        // Missing-field tolerance: incomplete payload -> skip (WARN), never crash.
        String payload = "{\"aggregateId\":1,\"occurredAt\":\"2026-09-01T10:15:30Z\"}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        assertEquals(0, auditAppender.list.size());
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void invalidJsonIsSkippedButStillAcknowledged() {
        // Poison pill: never blocks the subscription.
        consumer.handleMessage(pulsarConsumer, messageWithJson("not-json{"));

        assertEquals(0, auditAppender.list.size());
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void nullMessageDataIsAcknowledgedWithoutAudit() {
        @SuppressWarnings("unchecked")
        Message<byte[]> msg = mock(Message.class);
        when(msg.getData()).thenReturn(null);

        consumer.handleMessage(pulsarConsumer, msg);

        assertEquals(0, auditAppender.list.size());
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void reasonIsJsonEscapedInAuditEntry() {
        String payload = "{\"aggregateId\":9,\"userId\":13,"
                + "\"reason\":\"quoted \\\" and backslash \\\\\",\"occurredAt\":null}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        assertEquals(1, auditAppender.list.size());
        String json = auditAppender.list.get(0).getFormattedMessage();
        assertTrue(json.contains("\"reason\":\"quoted \\\" and backslash \\\\\""), json);
        // The emitted entry stays parseable JSON.
        com.fasterxml.jackson.databind.JsonNode node = parse(json);
        assertEquals("USER_FROZEN", node.get("action").asText());
        assertEquals(13, node.get("userId").asLong());
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError("AUDIT entry is not valid JSON: " + json, e);
        }
    }
}
