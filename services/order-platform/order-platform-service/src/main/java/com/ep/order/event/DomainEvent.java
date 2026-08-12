package com.ep.order.event;

import java.time.Instant;

/**
 * Domain event marker (lightweight, no framework dependency).
 * <p>
 * Aggregate roots (e.g. {@code OrderModel}) record {@link DomainEvent}
 * implementations into an internal collection that the application service
 * pulls (via {@code pullEvents()}) after a successful transaction and publishes
 * to the event bus (Pulsar).
 * <p>
 * NOTE: {@code ep-web-common} does NOT define a {@code DomainEvent} type, so
 * the order middle platform keeps a local one in {@code com.ep.order.event}.
 * If a shared {@code com.ep.common.DomainEvent} is introduced into
 * {@code ep-web-common} later, this interface should be removed and the events
 * switched to implement that one.
 */
public interface DomainEvent {

    long getAggregateId();

    Instant getOccurredAt();
}
