package com.ep.senior.care;

import com.ep.senior.common.DomainEvent;

import java.time.Instant;

/**
 * Emitted when a {@link CarePlan} transitions to ACTIVE.
 */
public record CarePlanActivatedEvent(long aggregateId, long planId,
                                      Long elderId, Instant occurredAt) implements DomainEvent {

    @Override
    public long getAggregateId() { return aggregateId; }

    @Override
    public Instant getOccurredAt() { return occurredAt; }
}
