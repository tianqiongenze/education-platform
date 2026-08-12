package com.ep.payment.api;

/**
 * Lifecycle of a {@link Payment} aggregate (OPM domain, SDD §3.1).
 * <pre>
 *   PENDING ──pay────▶ PROCESSING ──ok──▶ SUCCESS ──refund(full)──▶ REFUNDED
 *      │                    │                │
 *      │ timeout/gateway    │ gateway        │ partial refund: stays SUCCESS
 *      ▼                    ▼                ▼
 *    FAILED              FAILED            (multiple Refunds allowed)
 * </pre>
 * D-040: plain enum — GraalVM AOT friendly (no reflection tricks).
 */
public enum PaymentStatus {
    /** Created but the gateway call has not yet completed. */
    PENDING,
    /** Gateway call in flight (async channels). Mock channel skips this. */
    PROCESSING,
    /** Gateway confirmed the money movement. Only state from which refund is allowed. */
    SUCCESS,
    /** Gateway rejected / timed out / was cancelled. Terminal. */
    FAILED,
    /** Fully refunded. Reached when the sum of successful Refunds equals the Payment amount. */
    REFUNDED
}
