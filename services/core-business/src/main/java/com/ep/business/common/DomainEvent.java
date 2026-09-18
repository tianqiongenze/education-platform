package com.ep.business.common;

import com.ep.web.common.event.EventEnvelope;

import java.time.Instant;

/**
 * Domain event marker (lightweight, no framework dependency).
 * <p>
 * Aggregate roots record {@link DomainEvent} implementations into an internal
 * collection that the application service pulls (via {@code pullEvents()}) after
 * a successful transaction and publishes to the event bus (Pulsar, etc.).
 * <p>
 * D-event-shared: the platform-wide contract now lives in
 * {@code com.ep.web.common.event.DomainEvent} (ep-web-common). This local type
 * is retained rather than deleted because (a) the event records
 * ({@code CoursePublishedEvent(long aggregateId, …, Instant occurredAt)} etc.)
 * have record-component accessors whose signatures clash with the shared ones
 * ({@code String aggregateId()}, {@code long occurredAt()}), so they cannot
 * implement the shared interface by inheritance, and (b) aggregate roots and
 * application services (incl. {@code CourseServiceImpl}) reference this FQCN
 * directly. Every event lifts into the shared, versioned contract via
 * {@link #envelope()}; publishers use the envelope for structured failure
 * logging and retries.
 */
public interface DomainEvent {
    long getAggregateId();
    Instant getOccurredAt();

    /**
     * Shared-contract view of this event (eventId/eventType/occurredAt-millis/
     * String-aggregateId/schemaVersion). The deterministic eventId
     * ({@code eventType:aggregateId:occurredAtMillis}) lets consumers dedup
     * redeliveries idempotently.
     */
    default EventEnvelope envelope() {
        return EventEnvelope.of(getClass().getSimpleName(), getAggregateId(), getOccurredAt());
    }
}
