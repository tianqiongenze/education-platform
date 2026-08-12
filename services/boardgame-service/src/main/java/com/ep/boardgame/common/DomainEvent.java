package com.ep.boardgame.common;

/**
 * Domain event interface following the platform-wide DDD pattern.
 * Events are recorded by aggregate roots and pulled post-commit by services.
 */
public interface DomainEvent {

    /** The aggregate root ID that produced this event. */
    Long getAggregateId();

    /** When the event occurred (epoch millis). */
    long getOccurredAt();
}
