package com.ep.business.user;

import com.ep.business.common.DomainEvent;

import java.time.Instant;

/**
 * Emitted when a {@link User} transitions to FROZEN via
 * {@link User#freeze(String)}.
 */
public record UserFrozenEvent(long aggregateId, long userId, String reason,
                              Instant occurredAt) implements DomainEvent {

    @Override
    public long getAggregateId() {
        return aggregateId;
    }

    @Override
    public Instant getOccurredAt() {
        return occurredAt;
    }
}
