package com.ep.senior.health;

import com.ep.senior.common.DomainEvent;

import java.time.Instant;

/**
 * Emitted when a {@link HealthRecord} is evaluated as abnormal.
 */
public record HealthAbnormalDetectedEvent(long aggregateId, long elderId,
                                           Instant occurredAt) implements DomainEvent {

    @Override
    public long getAggregateId() { return aggregateId; }

    @Override
    public Instant getOccurredAt() { return occurredAt; }
}
