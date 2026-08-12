package com.ep.senior.profile;

import com.ep.senior.common.DomainEvent;

import java.time.Instant;

/**
 * Emitted when an {@link ElderProfile} is activated (created or resumed from PAUSED).
 */
public record ElderActivatedEvent(long aggregateId, long elderId,
                                   Long facilityId, Instant occurredAt) implements DomainEvent {

    @Override
    public long getAggregateId() { return aggregateId; }

    @Override
    public Instant getOccurredAt() { return occurredAt; }
}
