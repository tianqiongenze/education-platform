package com.ep.web.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EventEnvelope: lifts legacy per-service events (numeric/string ids, Instant
 * or epoch-millis timestamps) into the shared DomainEvent contract with a
 * deterministic eventId and default schemaVersion 1.
 */
class EventEnvelopeTest {

    @Test
    void liftsNumericAggregateIdAndInstantTimestamp() {
        Instant occurredAt = Instant.parse("2026-08-16T10:15:30Z");
        EventEnvelope envelope = EventEnvelope.of("CoursePublishedEvent", 42L, occurredAt);

        assertEquals("CoursePublishedEvent", envelope.eventType());
        assertEquals("42", envelope.aggregateId());
        assertEquals(occurredAt.toEpochMilli(), envelope.occurredAt());
        assertEquals("CoursePublishedEvent:42:" + occurredAt.toEpochMilli(), envelope.eventId());
        assertEquals(1, envelope.schemaVersion());
    }

    @Test
    void liftsStringAggregateIdAndInstantTimestamp() {
        Instant occurredAt = Instant.parse("2026-08-16T10:15:30Z");
        EventEnvelope envelope = EventEnvelope.of("DocumentParsedEvent", "1934567890123456789", occurredAt);

        assertEquals("1934567890123456789", envelope.aggregateId());
        assertEquals(occurredAt.toEpochMilli(), envelope.occurredAt());
        assertEquals("DocumentParsedEvent:1934567890123456789:" + occurredAt.toEpochMilli(), envelope.eventId());
    }

    @Test
    void liftsBoxedAggregateIdAndEpochMillisTimestamp() {
        EventEnvelope envelope = EventEnvelope.of("RoomStartedEvent", 7L, 1_755_336_930_000L);

        assertEquals("7", envelope.aggregateId());
        assertEquals(1_755_336_930_000L, envelope.occurredAt());
        assertEquals("RoomStartedEvent:7:1755336930000", envelope.eventId());
    }

    @Test
    void eventIdIsDeterministicForRedeliveryDedup() {
        Instant occurredAt = Instant.parse("2026-08-16T10:15:30Z");
        EventEnvelope first = EventEnvelope.of("CoursePublishedEvent", 42L, occurredAt);
        EventEnvelope redelivered = EventEnvelope.of("CoursePublishedEvent", 42L, occurredAt);
        assertEquals(first.eventId(), redelivered.eventId());
    }

    @Test
    void nullOccurredAtDegradesToZeroMillis() {
        EventEnvelope envelope = EventEnvelope.of("CoursePublishedEvent", 42L, (Instant) null);
        assertEquals(0L, envelope.occurredAt());
        assertEquals("CoursePublishedEvent:42:0", envelope.eventId());
    }

    @Test
    void envelopeImplementsSharedContract() {
        assertTrue(new EventEnvelope("id", "CoursePublishedEvent", 1L, "42") instanceof DomainEvent);
        DomainEvent shared = EventEnvelope.of("CoursePublishedEvent", 42L, Instant.now());
        assertEquals(1, shared.schemaVersion());
    }
}
