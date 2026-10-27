package com.ep.payment.agent;

/**
 * D-240/B: authorization boundary violation — fail-closed. Carries a stable
 * machine-readable {@code code} so the Dubbo translation
 * ({@code AgentPaymentRejectedException}) and the REST handler can branch
 * without parsing message text.
 */
public class AgentAuthorizationException extends RuntimeException {

    /** Machine-readable rejection code (e.g. {@code LIMIT_PER_TX}, {@code KYA_UNREGISTERED}). */
    private final String code;

    public AgentAuthorizationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** @deprecated use the coded constructor — message-only hides the failure class. */
    @Deprecated
    public AgentAuthorizationException(String message) {
        this("AGENT_AUTH_DENIED", message);
    }

    public String getCode() { return code; }
}
