package com.ep.boardgame.common;

import com.ep.web.common.event.EventEnvelope;

/**
 * Domain event interface following the platform-wide DDD pattern.
 * Events are recorded by aggregate roots and pulled post-commit by services.
 * <p>
 * D-event-shared: the platform-wide contract now lives in
 * {@code com.ep.web.common.event.DomainEvent} (ep-web-common). This local type
 * is retained rather than deleted because the event records
 * ({@code RoomStartedEvent(Long aggregateId, …, long occurredAt)}) have
 * record-component accessors whose signatures clash with the shared ones
 * ({@code String aggregateId()}), and {@code GameRoom}/{@code GameMatch}
 * reference this FQCN directly. Events lift into the shared, versioned
 * contract via {@link #envelope()}.
 */
public interface DomainEvent {

    /** The aggregate root ID that produced this event. */
    Long getAggregateId();

    /** When the event occurred (epoch millis). */
    long getOccurredAt();

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
