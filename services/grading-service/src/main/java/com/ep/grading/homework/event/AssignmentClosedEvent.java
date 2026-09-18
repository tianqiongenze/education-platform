package com.ep.grading.homework.event;

import com.ep.grading.common.DomainEvent;

import java.time.Instant;

/**
 * D-201: Emitted when an {@link com.ep.grading.homework.entity.Assignment}
 * transitions from PUBLISHED to CLOSED via {@code Assignment.close()}.
 */
public record AssignmentClosedEvent(long aggregateId, Long assignmentId, Long courseId,
                                    Long teacherId, Instant occurredAt) implements DomainEvent {

    @Override
    public long getAggregateId() {
        return aggregateId;
    }

    @Override
    public Instant getOccurredAt() {
        return occurredAt;
    }
}
