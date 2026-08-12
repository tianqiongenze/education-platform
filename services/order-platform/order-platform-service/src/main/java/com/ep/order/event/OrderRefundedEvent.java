package com.ep.order.event;

import java.time.Instant;

/**
 * Emitted when an order transitions from PAID to REFUNDED via
 * {@code OrderModel#refund()}.
 *
 * @param aggregateId  the order id (== orderId)
 * @param orderId      the order id
 * @param userId       the ordering user
 * @param courseId     product id (named courseId for back-compat; null for non-inventory products)
 * @param productType  product kind (e.g. COURSE, MEMBERSHIP)
 * @param occurredAt   event timestamp
 */
public record OrderRefundedEvent(long aggregateId, long orderId, Long userId, Long courseId,
                                String productType, Instant occurredAt) implements DomainEvent {

    @Override
    public long getAggregateId() {
        return aggregateId;
    }

    @Override
    public Instant getOccurredAt() {
        return occurredAt;
    }
}
