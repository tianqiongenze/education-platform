package com.ep.payment.api;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * D-240/B: command to create an <b>agent-driven</b> Payment (「AI支付」· L2 控制
 * 与授权层). Kept separate from the human {@link PaymentRequest} so the agent
 * security envelope is explicit in the Dubbo contract instead of overloading
 * nullable fields onto the existing human flow.
 *
 * <p>Server-side enforcement (fail-closed, in {@code PaymentServiceImpl}):
 * <ol>
 *   <li>{@code authorizationId} must reference an ACTIVE, in-window
 *       authorization whose per-tx and daily limits cover the amount.</li>
 *   <li>{@code paymentToken} is mandatory when the authorization mode is
 *       {@code CONFIRM_REQUIRED} (笔笔确认) — a one-time token issued by the
 *       principal's wallet console; consumed atomically.</li>
 *   <li>The payment records {@code agentId}/{@code authorizationId} for the
 *       五段式对账第5段 evidence chain.</li>
 * </ol>
 *
 * @param orderId         FK to the Order being paid
 * @param tenantId        D-044 multi-tenant scope
 * @param channel         requested {@link PaymentChannel}
 * @param amount          money to charge (must be {@code > 0})
 * @param currency        ISO-4217 code, e.g. "CNY"
 * @param agentId         KYA-registered agent identifier (never null)
 * @param authorizationId the principal's authorization covering this payment
 * @param paymentToken    one-time confirmation token (required for
 *                        CONFIRM_REQUIRED mode; optional/ignored otherwise)
 */
public record AgentPaymentRequest(String orderId, String tenantId, PaymentChannel channel,
                                  BigDecimal amount, String currency,
                                  String agentId, String authorizationId,
                                  String paymentToken) implements Serializable {

    /** True when this request carries the per-payment confirmation token. */
    public boolean hasToken() {
        return paymentToken != null && !paymentToken.isBlank();
    }
}
