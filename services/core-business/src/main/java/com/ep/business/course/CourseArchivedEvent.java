package com.ep.business.course;

import com.ep.business.common.DomainEvent;

import java.time.Instant;

/**
 * Emitted when a {@link Course} transitions from PUBLISHED to ARCHIVED via
 * {@link Course#archive()}.
 */
public record CourseArchivedEvent(long aggregateId, long courseId, Long teacherId,
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
