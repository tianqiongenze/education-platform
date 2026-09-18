package com.ep.knowledge.service.event;

import java.time.Instant;

/**
 * D-201: Emitted when a {@link com.ep.knowledge.service.entity.Document}
 * transitions from UPLOADED to FAILED via {@code Document.markFailed()}.
 */
public record DocumentFailedEvent(String aggregateId, String documentId, String tenantId,
                                  Instant occurredAt) implements DomainEvent {

    @Override
    public String getAggregateId() {
        return aggregateId;
    }

    @Override
    public Instant getOccurredAt() {
        return occurredAt;
    }
}
