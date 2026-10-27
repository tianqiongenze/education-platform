package com.ep.payment.api;

import java.io.Serializable;

/**
 * D-240/B: an agent payment was rejected by the L2 control layer — the
 * authorization is missing/inactive/expired, a limit was exceeded, or the
 * one-time confirmation token was invalid or replayed. Fail-closed: the
 * platform never silently degrades an unauthorised agent charge into an
 * allowed one.
 *
 * <p>Thrown from {@link PaymentService#createAgentPayment} across the Dubbo
 * wire; carries a stable {@code code} so clients can branch without parsing
 * message text.
 */
public class AgentPaymentRejectedException extends RuntimeException implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Stable machine-readable rejection code. */
    private final String code;

    public AgentPaymentRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AgentPaymentRejectedException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** e.g. "AUTH_INACTIVE", "LIMIT_PER_TX", "LIMIT_DAILY", "TOKEN_INVALID", "TOKEN_REPLAYED". */
    public String getCode() { return code; }
}
