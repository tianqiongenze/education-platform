package com.ep.payment.event;

import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Emitted when a {@code Payment} reaches a terminal (or semi-terminal) status
 * — SUCCESS / FAILED / REFUNDED — after a {@code createPayment} or
 * {@code refund} call. Carries just enough financial context for downstream
 * consumers (order platform, accounting) without leaking the whole aggregate.
 *
 * @param aggregateId  paymentId (== {@link #paymentId})
 * @param paymentId    OPM-internal Payment id (UUID)
 * @param orderId      FK to ep_order.order_id (the order this payment settles)
 * @param tenantId     multi-tenant scope (D-044)
 * @param channel      gateway channel that processed the payment
 * @param status       terminal status the Payment transitioned to
 * @param amount       settled amount (matches {@code Payment.amount})
 * @param currency     ISO-4217 currency
 * @param channelTxId  gateway transaction id (null before SUCCESS)
 * @param occurredAt   event timestamp
 */
public record PaymentResultEvent(String aggregateId, String paymentId, String orderId, String tenantId,
                                 PaymentChannel channel, PaymentStatus status, BigDecimal amount,
                                 String currency, String channelTxId, Instant occurredAt)
        implements PaymentEvent {

    public static final String TYPE = "payment-result";

    public PaymentResultEvent {
        if (aggregateId == null || aggregateId.isBlank()) {
            aggregateId = paymentId;
        }
    }

    @Override
    public String getAggregateId() {
        return aggregateId;
    }

    @Override
    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
