package com.ep.boardgame.room;

import com.ep.boardgame.common.DomainEvent;

/** Fired when a match finishes (win/draw). */
public record MatchFinishedEvent(Long aggregateId, int result, long occurredAt) implements DomainEvent {
    @Override
    public Long getAggregateId() { return aggregateId; }

    @Override
    public long getOccurredAt() { return occurredAt; }
}
