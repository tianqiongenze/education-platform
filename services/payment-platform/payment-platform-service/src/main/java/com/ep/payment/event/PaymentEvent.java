package com.ep.payment.event;

import java.time.Instant;

/**
 * Marker interface for payment-platform-service domain events emitted by the
 * OPM Payment / Refund aggregates. Mirrors the local {@code DomainEvent}
 * pattern used in core-business / order-platform-service so the disruptor
 * outbox pipeline can carry any concrete payment event.
 *
 * <p>The events are lightweight POJOs (records) with no framework dependency;
 * they are serialized to JSON by {@code PaymentEventPublisher} before being
 * pushed to Pulsar.</p>
 */
public interface PaymentEvent {

    /** Aggregate id this event refers to (paymentId for Payment events, refundId for Refund events). */
    String getAggregateId();

    /** Event occurrence timestamp. */
    Instant getOccurredAt();

    /** Stable type tag used as the Pulsar message key / routing attribute. */
    String getType();
}
