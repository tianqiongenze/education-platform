package com.ep.knowledge.tenant.event;

import com.ep.web.common.event.EventEnvelope;

import java.time.Instant;

/**
 * Domain event marker (lightweight, no framework dependency).
 * <p>
 * Aggregate roots record {@link DomainEvent} implementations into an internal
 * collection that the application service pulls (via {@code pullEvents()}) after
 * a successful transaction and publishes to the event bus (Pulsar, etc.).
 * <p>
 * D-201 DDD enrichment — follows the same pattern as
 * {@code com.ep.business.common.DomainEvent} in core-business. Uses String
 * aggregate IDs to match the MyBatis-Plus {@code IdType.ASSIGN_ID} convention
 * in tenant-service.
 * <p>
 * D-event-shared: the platform-wide contract now lives in
 * {@code com.ep.web.common.event.DomainEvent} (ep-web-common). This local type
 * is retained rather than deleted because the event records
 * ({@code TenantActivatedEvent(String aggregateId, …, Instant occurredAt)})
 * have record-component accessors whose signature ({@code Instant occurredAt()})
 * clashes with the shared one ({@code long occurredAt()}), and the
 * {@code Tenant} aggregate references this FQCN directly. Events lift into the
 * shared, versioned contract via {@link #envelope()}.
 */
public interface DomainEvent {
    String getAggregateId();
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
