package com.ep.payment.api;

import java.io.Serializable;
import java.time.Instant;

/**
 * Outcome of a {@link PaymentChannelSpi#pay} call. Returned to the caller of
 * {@link PaymentService#createPayment}. Immutable value object.
 *
 * <p>D-040: record — GraalVM AOT friendly.
 * Serializable so Dubbo can marshal it across the wire to remote consumers.
 *
 * @param paymentId   the OPM-internal Payment id (UUID)
 * @param status      terminal/intermediate status after the gateway call
 * @param channelTxId the gateway's transaction id (null until SUCCESS)
 * @param paidAt      the confirmed paid-at instant (null until SUCCESS)
 */
public record PaymentResult(String paymentId, PaymentStatus status, String channelTxId,
                            Instant paidAt) implements Serializable {
}
