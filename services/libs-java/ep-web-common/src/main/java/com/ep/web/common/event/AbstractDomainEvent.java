package com.ep.web.common.event;

import java.util.UUID;

/**
 * Convenience base class for shared-contract domain events (D-event-shared).
 * <p>
 * Fills the infrastructure fields of {@link DomainEvent}:
 * a random {@link UUID} {@code eventId} and {@link System#currentTimeMillis()}
 * {@code occurredAt} are captured once in the constructor, so repeated calls
 * return the same values. {@link #eventType()} defaults to the concrete class
 * simple name; {@link #aggregateId()} remains abstract because it is
 * domain-specific.
 * <p>
 * Java records cannot extend classes; record-based events should implement
 * {@link DomainEvent} directly or (legacy events) be lifted into the contract
 * via {@link EventEnvelope}.
 */
public abstract class AbstractDomainEvent implements DomainEvent {

    private final String eventId;
    private final long occurredAt;

    /**
     * Captures a fresh event id and occurrence timestamp.
     */
    protected AbstractDomainEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = System.currentTimeMillis();
    }

    @Override
    public String eventId() {
        return eventId;
    }

    @Override
    public long occurredAt() {
        return occurredAt;
    }

    @Override
    public String eventType() {
        return getClass().getSimpleName();
    }
}
