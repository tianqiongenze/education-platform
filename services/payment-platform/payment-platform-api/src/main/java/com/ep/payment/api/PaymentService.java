package com.ep.payment.api;

import java.math.BigDecimal;

/**
 * OPM Payment application service (SDD §3). Entry point for the order flow
 * and for any future payment-facing API. Implemented by the service module's
 * {@code PaymentServiceImpl}.
 *
 * <p>This interface lives in the thin API jar so consumers (e.g.
 * order-platform-service) can call it over Dubbo without dragging in the
 * service's Spring Boot runtime.
 */
public interface PaymentService {

    /**
     * Drive a charge for the given order through the requested channel.
     *
     * @param request the charge request (orderId, tenantId, channel, amount, currency)
     * @return the Payment result (status, channelTxId, paidAt)
     */
    PaymentResult createPayment(PaymentRequest request);

    /** Look up a Payment by its id. */
    Payment getPayment(String paymentId);

    /** Look up the (latest) Payment for an order. */
    Payment getPaymentByOrder(String orderId);

    /**
     * Refund an order's payment.
     *
     * @param orderId the order whose Payment should be refunded
     * @param amount  refund amount (may be partial; full amount flips Payment to REFUNDED)
     * @param reason  free-text reason
     * @return the recorded Refund entity
     */
    Refund refund(String orderId, BigDecimal amount, String reason);
}
