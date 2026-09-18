package com.ep.grading.common;

import com.ep.web.common.event.EventEnvelope;

import java.time.Instant;

/**
 * Domain event marker (lightweight, no framework dependency).
 * <p>
 * Aggregate roots record {@link DomainEvent} implementations into an internal
 * collection that the application service pulls (via {@code pullEvents()}) after
 * a successful transaction and publishes to the event bus (Pulsar, etc.).
 * <p>
 * Follows the same pattern as {@code com.ep.business.common.DomainEvent} in
 * core-business (D-201 DDD enrichment).
 * <p>
 * D-event-shared: the platform-wide contract now lives in
 * {@code com.ep.web.common.event.DomainEvent} (ep-web-common). This local type
 * is retained rather than deleted because the event records
 * ({@code AssignmentPublishedEvent(long aggregateId, …, Instant occurredAt)})
 * have record-component accessors whose signatures clash with the shared ones
 * ({@code String aggregateId()}, {@code long occurredAt()}), and
 * {@code Assignment}/{@code DisruptorEventConfig} reference this FQCN directly.
 * Events lift into the shared, versioned contract via {@link #envelope()}.
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
