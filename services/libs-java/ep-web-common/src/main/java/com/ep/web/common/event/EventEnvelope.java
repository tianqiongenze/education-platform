package com.ep.web.common.event;

import java.time.Instant;

/**
 * Adapts a legacy per-service domain event into the shared
 * {@link DomainEvent} contract (D-event-shared).
 * <p>
 * Legacy events are Java records whose component accessors
 * ({@code long aggregateId()}, {@code Instant occurredAt()}, …) clash with the
 * shared interface signatures ({@code String aggregateId()},
 * {@code long occurredAt()}), so they cannot implement the contract via
 * inheritance. {@code EventEnvelope} wraps the identifying fields instead —
 * each service-local {@code DomainEvent} interface exposes a default
 * {@code envelope()} method and the publishers hand the envelope to
 * {@link EventSendRetrySupport} for structured logging and retries.
 * <p>
 * {@link #eventId()} is <b>deterministic</b> ({@code eventType:aggregateId:occurredAtMillis})
 * rather than a random UUID: the legacy records carry no stored id, and a
 * deterministic id gives consumers a stable idempotency key for dedup when the
 * same event is redelivered.
 */
public record EventEnvelope(String eventId, String eventType, long occurredAt,
                            String aggregateId) implements DomainEvent {

    /** Deterministic eventId format: {@code eventType:aggregateId:occurredAtMillis}. */
    static String buildEventId(String eventType, String aggregateId, long occurredAt) {
        return eventType + ":" + aggregateId + ":" + occurredAt;
    }

    /** Lift a legacy event with numeric id + {@link Instant} timestamp (core-business, order, grading, senior-care). */
    public static EventEnvelope of(String eventType, long aggregateId, Instant occurredAt) {
        return of(eventType, String.valueOf(aggregateId), occurredAt);
    }

    /** Lift a legacy event with boxed numeric id + epoch-millis timestamp (boardgame-service). */
    public static EventEnvelope of(String eventType, Long aggregateId, long occurredAtMillis) {
        return new EventEnvelope(
                buildEventId(eventType, String.valueOf(aggregateId), occurredAtMillis),
                eventType, occurredAtMillis, String.valueOf(aggregateId));
    }

    /** Lift a legacy event with string id + {@link Instant} timestamp (knowledge-platform). */
    public static EventEnvelope of(String eventType, String aggregateId, Instant occurredAt) {
        long millis = occurredAt == null ? 0L : occurredAt.toEpochMilli();
        return new EventEnvelope(buildEventId(eventType, aggregateId, millis), eventType, millis, aggregateId);
    }
}
