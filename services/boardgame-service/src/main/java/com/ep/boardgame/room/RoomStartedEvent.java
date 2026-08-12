package com.ep.boardgame.room;

import com.ep.boardgame.common.DomainEvent;

/** Fired when a game room transitions to PLAYING. */
public record RoomStartedEvent(Long aggregateId, Long matchId, long occurredAt) implements DomainEvent {
    @Override
    public Long getAggregateId() { return aggregateId; }

    @Override
    public long getOccurredAt() { return occurredAt; }
}
