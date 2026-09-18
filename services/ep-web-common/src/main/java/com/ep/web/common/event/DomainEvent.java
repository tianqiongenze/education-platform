package com.ep.web.common.event;

/**
 * Shared domain-event contract for all education-platform services (D-event-shared).
 * <p>
 * Replaces the 5+ per-service copies of a {@code DomainEvent} marker interface
 * (e.g. {@code com.ep.order.event.DomainEvent},
 * {@code com.ep.business.common.DomainEvent}) with one canonical definition in
 * {@code ep-web-common}. Every event carries:
 * <ul>
 *   <li>{@link #eventId()} — stable identifier of this event occurrence, used
 *       for logging, consumer idempotency and (future) outbox bookkeeping;</li>
 *   <li>{@link #eventType()} — logical type name (convention:
 *       {@code <Aggregate><Transition>Event}, e.g. {@code CoursePublishedEvent});</li>
 *   <li>{@link #occurredAt()} — epoch millis of when the event happened;</li>
 *   <li>{@link #aggregateId()} — aggregate root id that produced the event
 *       (String so numeric and string id strategies — MyBatis-Plus
 *       {@code ASSIGN_ID} vs auto-increment — share one contract);</li>
 *   <li>{@link #schemaVersion()} — payload schema version. Consumers must
 *       tolerate unknown higher versions (forward compatibility). Defaults to
 *       {@code 1}; bump only when the JSON payload shape changes in a
 *       non-additive way. The AsyncAPI specs record the same version.</li>
 * </ul>
 * <p>
 * Legacy per-service event records (Java {@code record}s with components named
 * {@code aggregateId}/{@code occurredAt} of different types) cannot implement
 * this interface directly — their record component accessors
 * ({@code long aggregateId()}, {@code Instant occurredAt()}) clash with these
 * method signatures. Those events are lifted into this contract at the
 * publishing boundary via {@link EventEnvelope} (see each service-local
 * {@code DomainEvent#envelope()} bridge). New events should prefer extending
 * {@link AbstractDomainEvent} or implementing this interface directly.
 */
public interface DomainEvent {

    /** Unique identifier of this event occurrence (never {@code null}). */
    String eventId();

    /** Logical event type name, e.g. {@code CoursePublishedEvent}. */
    String eventType();

    /** When the event occurred, epoch milliseconds. */
    long occurredAt();

    /** Aggregate root id that produced the event (stringified). */
    String aggregateId();

    /**
     * Payload schema version of this event. Defaults to {@code 1}.
     * Consumers should reject or route versions they do not understand
     * rather than fail silently.
     */
    default int schemaVersion() {
        return 1;
    }
}
