package com.ep.business.course;

import com.ep.business.common.DomainEvent;

import java.time.Instant;

/**
 * Emitted when a {@link Course} transitions from DRAFT to PUBLISHED via
 * {@link Course#publish()}.
 */
public record CoursePublishedEvent(long aggregateId, long courseId, Long teacherId,
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
