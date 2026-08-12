package com.ep.senior.profile;

import com.ep.senior.common.DomainEvent;

import java.time.Instant;

/**
 * Emitted when an {@link ElderProfile} transitions to DISCHARGED.
 */
public record ElderDischargedEvent(long aggregateId, long elderId,
                                    Long facilityId, Instant occurredAt) implements DomainEvent {

    @Override
    public long getAggregateId() { return aggregateId; }

    @Override
    public Instant getOccurredAt() { return occurredAt; }
}
