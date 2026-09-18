package com.ep.bff.event;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CoursePublishedConsumer tests: pure JUnit + Mockito (no Spring context, no
 * broker — same pattern as services/integration-tests EndToEnd*Test). Feeds
 * JSON through the package-private {@code handleMessage} entry point with a
 * mocked Pulsar {@link Consumer}/{@link Message} and verifies the
 * {@link CourseWarmupClient} port plus poison-pill-safe acknowledgement.
 */
class CoursePublishedConsumerTest {

    private CourseWarmupClient warmupClient;
    private CoursePublishedConsumer consumer;
    private Consumer<byte[]> pulsarConsumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        warmupClient = mock(CourseWarmupClient.class);
        ObjectProvider<org.apache.pulsar.client.api.PulsarClient> clientProvider = mock(ObjectProvider.class);
        consumer = new CoursePublishedConsumer(clientProvider, warmupClient,
                "persistent://public/default/ep-business-domain-events", "test-subscription");
        pulsarConsumer = mock(Consumer.class);
    }

    @SuppressWarnings("unchecked")
    private Message<byte[]> messageWithJson(String json) {
        Message<byte[]> msg = mock(Message.class);
        when(msg.getData()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        return msg;
    }

    @Test
    void coursePublishedPayloadTriggersCourseDetailWarmup() {
        String payload = "{\"aggregateId\":42,\"courseId\":42,\"teacherId\":7,"
                + "\"occurredAt\":\"2026-08-16T10:15:30Z\"}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        verify(warmupClient).warmupCourseDetail(42L);
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void payloadWithExtraFieldsIsTolerated() {
        String payload = "{\"aggregateId\":42,\"courseId\":99,\"teacherId\":7,"
                + "\"occurredAt\":\"2026-08-16T10:15:30Z\",\"futureField\":\"x\"}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        verify(warmupClient).warmupCourseDetail(99L);
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void nonCourseEventWithoutCourseIdIsSkippedButAcknowledged() {
        // UserFrozenEvent shape: no courseId -> not a course event, skip warm-up.
        String payload = "{\"aggregateId\":9,\"userId\":9,\"reason\":\"fraud\","
                + "\"occurredAt\":\"2026-08-16T10:15:30Z\"}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        verify(warmupClient, never()).warmupCourseDetail(anyLong());
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void invalidJsonIsSkippedButStillAcknowledged() {
        // Poison pill: never blocks the subscription.
        consumer.handleMessage(pulsarConsumer, messageWithJson("not-json{"));

        verify(warmupClient, never()).warmupCourseDetail(anyLong());
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void nullMessageDataIsAcknowledgedWithoutWarmup() {
        @SuppressWarnings("unchecked")
        Message<byte[]> msg = mock(Message.class);
        when(msg.getData()).thenReturn(null);

        consumer.handleMessage(pulsarConsumer, msg);

        verify(warmupClient, never()).warmupCourseDetail(anyLong());
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void zeroOrNegativeCourseIdIsSkipped() {
        String payload = "{\"aggregateId\":0,\"courseId\":0,\"occurredAt\":\"2026-08-16T10:15:30Z\"}";

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        verify(warmupClient, never()).warmupCourseDetail(anyLong());
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }

    @Test
    void warmupFailureDoesNotBlockAcknowledgement() {
        String payload = "{\"aggregateId\":42,\"courseId\":42,\"occurredAt\":\"2026-08-16T10:15:30Z\"}";
        org.mockito.Mockito.doThrow(new RuntimeException("core-business down"))
                .when(warmupClient).warmupCourseDetail(42L);

        consumer.handleMessage(pulsarConsumer, messageWithJson(payload));

        verify(warmupClient).warmupCourseDetail(42L);
        verify(pulsarConsumer).acknowledgeAsync(any(Message.class));
    }
}
