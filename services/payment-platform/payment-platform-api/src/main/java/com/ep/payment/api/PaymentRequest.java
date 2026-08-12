package com.ep.payment.api;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Command to create a Payment (input to {@link PaymentChannelSpi#pay} and
 * {@link PaymentService#createPayment}). Immutable value object.
 *
 * <p>D-040: record — GraalVM AOT friendly.
 * Serializable so Dubbo can marshal it across the wire to remote consumers.
 *
 * @param orderId   FK to the Order being paid
 * @param tenantId  D-044 multi-tenant scope
 * @param channel   requested {@link PaymentChannel}
 * @param amount    money to charge (must be {@code > 0})
 * @param currency  ISO-4217 code, e.g. "CNY"
 */
public record PaymentRequest(String orderId, String tenantId, PaymentChannel channel,
                             BigDecimal amount, String currency) implements Serializable {
}
