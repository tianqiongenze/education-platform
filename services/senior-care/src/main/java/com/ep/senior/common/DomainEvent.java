package com.ep.senior.common;

import java.time.Instant;

/**
 * Domain event marker (lightweight, no framework dependency).
 * <p>
 * Aggregate roots record {@link DomainEvent} implementations into an internal
 * collection that the application service pulls (via {@code pullEvents()}) after
 * a successful transaction and publishes to the event bus (Pulsar).
 */
public interface DomainEvent {
    long getAggregateId();
    Instant getOccurredAt();
}
