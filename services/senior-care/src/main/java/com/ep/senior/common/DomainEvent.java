package com.ep.senior.common;

import com.ep.web.common.event.EventEnvelope;

import java.time.Instant;

/**
 * Domain event marker (lightweight, no framework dependency).
 * <p>
 * Aggregate roots record {@link DomainEvent} implementations into an internal
 * collection that the application service pulls (via {@code pullEvents()}) after
 * a successful transaction and publishes to the event bus (Pulsar).
 * <p>
 * D-event-shared: the platform-wide contract now lives in
 * {@code com.ep.web.common.event.DomainEvent} (ep-web-common). This local type
 * is retained rather than deleted because the event records
 * ({@code CarePlanActivatedEvent(long aggregateId, …, Instant occurredAt)})
 * have record-component accessors whose signatures clash with the shared ones
 * ({@code String aggregateId()}, {@code long occurredAt()}), and the aggregate
 * roots ({@code CarePlan}, {@code ElderProfile}, {@code HealthRecord}) and
 * service impls reference this FQCN directly. Events lift into the shared,
 * versioned contract via {@link #envelope()}.
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
