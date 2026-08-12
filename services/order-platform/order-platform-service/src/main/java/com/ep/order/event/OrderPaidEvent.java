package com.ep.order.event;

import java.time.Instant;

/**
 * Emitted when an order transitions from PENDING to PAID via
 * {@code OrderModel#pay(String)}.
 *
 * @param aggregateId  the order id (== orderId)
 * @param orderId      the order id
 * @param userId       the ordering user
 * @param courseId     product id (named courseId for back-compat; null for non-inventory products)
 * @param productType  product kind (e.g. COURSE, MEMBERSHIP)
 * @param payMethod    free-text pay method resolved to a PaymentChannel
 * @param occurredAt   event timestamp
 */
public record OrderPaidEvent(long aggregateId, long orderId, Long userId, Long courseId,
                             String productType, String payMethod, Instant occurredAt) implements DomainEvent {

    @Override
    public long getAggregateId() {
        return aggregateId;
    }

    @Override
    public Instant getOccurredAt() {
        return occurredAt;
    }
}
