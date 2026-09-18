package com.ep.knowledge.tenant.event;

import java.time.Instant;

/**
 * D-201: Emitted when a {@link com.ep.knowledge.tenant.entity.Tenant}
 * transitions from PENDING to ACTIVE via {@code Tenant.activate()}.
 */
public record TenantActivatedEvent(String aggregateId, String tenantId, String plan,
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
