package com.ep.business.event;

import com.ep.business.course.CoursePublishedEvent;
import com.ep.web.common.event.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D-event-shared: proves a real core-business event record lifts into the
 * shared {@link DomainEvent} contract via the local interface's
 * {@code envelope()} bridge, carrying schemaVersion (default 1) and a
 * deterministic eventId for consumer-side dedup.
 */
class LegacyEventEnvelopeTest {

    @Test
    void coursePublishedEventLiftsIntoSharedContract() {
        Instant occurredAt = Instant.parse("2026-08-16T10:15:30Z");
        CoursePublishedEvent event = new CoursePublishedEvent(42L, 42L, 7L, occurredAt);

        DomainEvent shared = event.envelope();

        assertEquals("CoursePublishedEvent", shared.eventType());
        assertEquals("42", shared.aggregateId());
        assertEquals(occurredAt.toEpochMilli(), shared.occurredAt());
        assertEquals("CoursePublishedEvent:42:" + occurredAt.toEpochMilli(), shared.eventId());
        assertEquals(1, shared.schemaVersion());
    }
}
