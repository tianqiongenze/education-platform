package com.ep.knowledge.service.event;

import java.time.Instant;

/**
 * D-201: Emitted when a {@link com.ep.knowledge.service.entity.Document}
 * transitions from UPLOADED to PARSED via {@code Document.markParsed(int)}.
 */
public record DocumentParsedEvent(String aggregateId, String documentId, String tenantId,
                                  int chunkCount, Instant occurredAt) implements DomainEvent {

    @Override
    public String getAggregateId() {
        return aggregateId;
    }

    @Override
    public Instant getOccurredAt() {
        return occurredAt;
    }
}
