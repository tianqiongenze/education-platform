package com.ep.web.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EventSendRetrySupport: bounded retries with linear backoff, structured
 * failure logging seam (EventFailureHandler = future outbox hook), never
 * throws and never completes exceptionally.
 */
class EventSendRetrySupportTest {

    private static DomainEvent sampleEvent() {
        return EventEnvelope.of("CoursePublishedEvent", 42L, Instant.parse("2026-08-16T10:15:30Z"));
    }

    private static CompletableFuture<Object> failedFuture(String message) {
        CompletableFuture<Object> f = new CompletableFuture<>();
        f.completeExceptionally(new RuntimeException(message));
        return f;
    }

    @Test
    void succeedsOnFirstAttemptWithoutRetry() {
        AtomicInteger sends = new AtomicInteger();

        CompletableFuture<Boolean> result = EventSendRetrySupport.sendWithRetry(
                () -> {
                    sends.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                },
                sampleEvent(), 2, 1L);

        assertTrue(result.join());
        assertEquals(1, sends.get());
    }

    @Test
    void retriesTwiceAndSucceedsOnThirdAttempt() {
        // Producer-mock style: first two sends fail, third succeeds.
        AtomicInteger sends = new AtomicInteger();

        CompletableFuture<Boolean> result = EventSendRetrySupport.sendWithRetry(
                () -> {
                    int attempt = sends.incrementAndGet();
                    return attempt < 3 ? failedFuture("boom " + attempt) : CompletableFuture.completedFuture(null);
                },
                sampleEvent(), 2, 1L);

        assertTrue(result.join());
        assertEquals(3, sends.get(), "initial attempt + 2 retries = 3 sends");
    }

    @Test
    void dropsEventAfterExhaustedRetriesAndNotifiesFailureHandler() {
        AtomicInteger sends = new AtomicInteger();
        List<DomainEvent> dropped = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        List<Integer> attemptCounts = new ArrayList<>();
        Instant occurredAt = Instant.parse("2026-08-16T10:15:30Z");

        CompletableFuture<Boolean> result = EventSendRetrySupport.sendWithRetry(
                () -> {
                    sends.incrementAndGet();
                    return failedFuture("always down");
                },
                EventEnvelope.of("CoursePublishedEvent", 42L, occurredAt), 2, 1L,
                (event, error, attempts) -> {
                    dropped.add(event);
                    errors.add(error);
                    attemptCounts.add(attempts);
                });

        assertFalse(result.join(), "terminal failure completes false, never exceptionally");
        assertEquals(3, sends.get(), "initial attempt + 2 retries");
        assertEquals(1, dropped.size());
        // Structured fields available to the WARN log / future outbox row.
        assertEquals("CoursePublishedEvent", dropped.get(0).eventType());
        assertEquals("42", dropped.get(0).aggregateId());
        assertEquals(1, dropped.get(0).schemaVersion());
        assertEquals("CoursePublishedEvent:42:" + occurredAt.toEpochMilli(), dropped.get(0).eventId());
        assertEquals(3, attemptCounts.get(0));
        assertEquals(RuntimeException.class, errors.get(0).getClass());
    }

    @Test
    void unwrapsCompletionExceptionForTheFailureHandler() {
        List<Throwable> errors = new ArrayList<>();

        EventSendRetrySupport.sendWithRetry(
                        () -> CompletableFuture.failedFuture(new IllegalStateException("broker down")),
                        sampleEvent(), 0, 1L,
                        (event, error, attempts) -> errors.add(error))
                .join();

        assertEquals(IllegalStateException.class, errors.get(0).getClass(),
                "handler must see the root cause, not the CompletionException wrapper");
    }

    @Test
    void synchronousSendExceptionIsRetried() {
        AtomicInteger sends = new AtomicInteger();

        CompletableFuture<Boolean> result = EventSendRetrySupport.sendWithRetry(
                () -> {
                    if (sends.incrementAndGet() < 2) {
                        throw new IllegalStateException("producer closed");
                    }
                    return CompletableFuture.completedFuture(null);
                },
                sampleEvent(), 2, 1L);

        assertTrue(result.join());
        assertEquals(2, sends.get());
    }

    @Test
    void nullArgumentsCompleteFalseWithoutSending() {
        AtomicInteger sends = new AtomicInteger();

        CompletableFuture<Boolean> nullAction = EventSendRetrySupport.sendWithRetry(
                () -> {
                    sends.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }, null, 2, 1L);
        CompletableFuture<Boolean> nullEvent = EventSendRetrySupport.sendWithRetry(null, sampleEvent(), 2, 1L);

        assertFalse(nullAction.join());
        assertFalse(nullEvent.join());
        assertEquals(0, sends.get());
    }

    @Test
    void asyncFailurePathMatchesProducerCallbackShape() {
        // Mirrors Pulsar producer.sendAsync semantics: failures may arrive as
        // an exceptionally-completed future wrapped in CompletionException.
        AtomicInteger sends = new AtomicInteger();

        CompletableFuture<Boolean> result = EventSendRetrySupport.sendWithRetry(
                () -> {
                    if (sends.incrementAndGet() < 2) {
                        CompletableFuture<Object> f = new CompletableFuture<>();
                        f.completeExceptionally(new CompletionException(new RuntimeException("timeout")));
                        return f;
                    }
                    return CompletableFuture.completedFuture(null);
                },
                sampleEvent(), 2, 1L);

        assertTrue(result.join());
        assertEquals(2, sends.get());
    }
}
