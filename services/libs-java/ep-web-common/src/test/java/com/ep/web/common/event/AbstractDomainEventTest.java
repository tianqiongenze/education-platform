package com.ep.web.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AbstractDomainEvent: constructor fills eventId (UUID) and occurredAt
 * (System.currentTimeMillis) once and stably; eventType() defaults to the
 * concrete class simple name; schemaVersion() defaults to 1.
 */
class AbstractDomainEventTest {

    static class SamplePublishedEvent extends AbstractDomainEvent {
        private final long aggregateId;

        SamplePublishedEvent(long aggregateId) {
            this.aggregateId = aggregateId;
        }

        @Override
        public String aggregateId() {
            return String.valueOf(aggregateId);
        }
    }

    @Test
    void constructorFillsEventIdAsUuid() {
        SamplePublishedEvent event = new SamplePublishedEvent(42L);

        assertNotNull(event.eventId());
        assertTrue(!event.eventId().isBlank(), "eventId must not be blank");
        // UUID-shaped (36 chars with 4 dashes) — asserts the documented UUID policy.
        assertEquals(36, event.eventId().length());
        assertEquals(4, event.eventId().chars().filter(c -> c == '-').count());
    }

    @Test
    void eventIdsAreUniquePerInstance() {
        assertNotEquals(new SamplePublishedEvent(1L).eventId(), new SamplePublishedEvent(1L).eventId());
    }

    @Test
    void constructorFillsOccurredAtWithCurrentTimeMillisStably() {
        long before = System.currentTimeMillis();
        SamplePublishedEvent event = new SamplePublishedEvent(42L);
        long after = System.currentTimeMillis();

        assertTrue(event.occurredAt() >= before && event.occurredAt() <= after,
                "occurredAt must be captured at construction time");
        assertEquals(event.occurredAt(), event.occurredAt(), "occurredAt must be stable across calls");
    }

    @Test
    void eventTypeDefaultsToConcreteClassSimpleName() {
        assertEquals("SamplePublishedEvent", new SamplePublishedEvent(42L).eventType());
    }

    @Test
    void schemaVersionDefaultsToOne() {
        assertEquals(1, new SamplePublishedEvent(42L).schemaVersion());
    }

    @Test
    void implementsSharedContract() {
        DomainEvent shared = new SamplePublishedEvent(42L);
        assertEquals("42", shared.aggregateId());
        assertEquals(Instant.ofEpochMilli(shared.occurredAt()).toEpochMilli(), shared.occurredAt());
    }
}
