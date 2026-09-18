package com.ep.order.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-217: Pure Mockito unit tests for {@link OutboxRelayService}.
 * <p>
 * Covers the four relay behaviours:
 * <ol>
 *   <li>happy path — unsent rows are forwarded to Pulsar and stamped
 *       {@code published_at};</li>
 *   <li>publish failure — {@code retry_count} is incremented and
 *       {@code published_at} stays null so the next tick retries;</li>
 *   <li>max-retries — once {@code retry_count} reaches {@link OutboxRelayService#MAX_RETRIES}
 *       the row is marked published to stop the infinite-retry loop;</li>
 *   <li>empty outbox — the relay is a no-op and never touches Pulsar.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRelayServiceTest {

    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private OutboxPublisher publisher;

    private OutboxRelayService relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelayService(outboxRepository, publisher);
        // save() returns its argument so the relay's in-place mutation is observable.
        when(outboxRepository.save(any(OutboxEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private OutboxEntity newRow(String eventId, String eventType, int retryCount) {
        OutboxEntity e = new OutboxEntity();
        e.setEventId(eventId);
        e.setAggregateType("Order");
        e.setAggregateId("1");
        e.setEventType(eventType);
        e.setPayload("{}");
        e.setCreatedAt(Instant.now());
        e.setRetryCount(retryCount);
        return e;
    }

    // ------------------------------------------------------------------
    // 1. happy path
    // ------------------------------------------------------------------

    @Test
    void unpublishedEventsAreRelayedToPulsar() throws Exception {
        OutboxEntity a = newRow("evt-1", "OrderPaidEvent", 0);
        OutboxEntity b = newRow("evt-2", "OrderRefundedEvent", 0);
        when(outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(a, b));

        relay.relay();

        // both forwarded to Pulsar with their event type + payload
        verify(publisher, times(1)).publishRaw("OrderPaidEvent", "{}");
        verify(publisher, times(1)).publishRaw("OrderRefundedEvent", "{}");
        // both saved with published_at stamped
        ArgumentCaptor<OutboxEntity> captor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepository, times(2)).save(captor.capture());
        for (OutboxEntity saved : captor.getAllValues()) {
            assertNotNull(saved.getPublishedAt(), "published_at must be set on success");
        }
    }

    // ------------------------------------------------------------------
    // 2. publish failure increments retry count, published_at stays null
    // ------------------------------------------------------------------

    @Test
    void publishFailureIncrementsRetryCount() throws Exception {
        OutboxEntity a = newRow("evt-1", "OrderPaidEvent", 0);
        when(outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(a));
        org.mockito.Mockito.doThrow(new IllegalStateException("pulsar down"))
                .when(publisher).publishRaw(anyString(), anyString());

        relay.relay();

        assertEquals(1, a.getRetryCount(), "retry_count must be incremented on failure");
        assertNull(a.getPublishedAt(), "published_at must stay null on failure so the next tick retries");
        verify(outboxRepository, times(1)).save(a);
    }

    // ------------------------------------------------------------------
    // 3. max retries marks as published to stop infinite loop
    // ------------------------------------------------------------------

    @Test
    void maxRetriesMarksAsPublishedToStopInfiniteLoop() throws Exception {
        // retryCount one below the threshold; a failure pushes it to MAX_RETRIES
        // and the relay gives up (marks published_at).
        OutboxEntity a = newRow("evt-1", "OrderPaidEvent", OutboxRelayService.MAX_RETRIES - 1);
        when(outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(a));
        org.mockito.Mockito.doThrow(new IllegalStateException("pulsar down"))
                .when(publisher).publishRaw(anyString(), anyString());

        relay.relay();

        assertEquals(OutboxRelayService.MAX_RETRIES, a.getRetryCount(),
                "retry_count must reach MAX_RETRIES");
        assertNotNull(a.getPublishedAt(),
                "published_at must be stamped at MAX_RETRIES to stop the infinite-retry loop");
        verify(outboxRepository, times(1)).save(a);
    }

    // ------------------------------------------------------------------
    // 4. empty outbox is a no-op
    // ------------------------------------------------------------------

    @Test
    void emptyOutboxDoesNothing() throws Exception {
        when(outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of());

        relay.relay();

        verify(publisher, never()).publishRaw(anyString(), anyString());
        verify(outboxRepository, never()).save(any(OutboxEntity.class));
    }

    // ------------------------------------------------------------------
    // 5. successful publish sets published_at
    // ------------------------------------------------------------------

    @Test
    void successfulPublishSetsPublishedAt() throws Exception {
        OutboxEntity a = newRow("evt-1", "OrderPaidEvent", 0);
        when(outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(a));

        relay.relay();

        assertNotNull(a.getPublishedAt(), "published_at must be set after a successful publish");
        assertEquals(0, a.getRetryCount(), "retry_count must stay 0 on success");
        verify(publisher, times(1)).publishRaw("OrderPaidEvent", "{}");
    }
}
