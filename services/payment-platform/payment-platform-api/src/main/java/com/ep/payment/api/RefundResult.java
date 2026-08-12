package com.ep.payment.api;

import java.io.Serializable;

/**
 * Outcome of a {@link PaymentChannelSpi#refund} call. Immutable value object.
 *
 * <p>D-040: record — GraalVM AOT friendly.
 * Serializable so Dubbo can marshal it across the wire to remote consumers.
 *
 * @param success           whether the gateway accepted the refund
 * @param channelRefundId   the gateway's refund id (null on failure)
 * @param message           human-readable detail / failure reason
 */
public record RefundResult(boolean success, String channelRefundId, String message) implements Serializable {
}
