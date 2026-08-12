package com.ep.payment.api;

/**
 * Lifecycle of a {@link Refund} (OPM domain, SDD §3.1: a Payment may have
 * many Refunds). Mirrors the {@code status TINYINT '1处理中2成功3失败'}
 * column on {@code ep_refund}.
 *
 * <p>D-040: plain enum — GraalVM AOT friendly.
 */
public enum RefundStatus {
    /** Refund requested, gateway call in flight (or awaiting callback). */
    PENDING,
    /** Gateway confirmed the refund landed. Terminal. */
    SUCCESS,
    /** Gateway rejected the refund. Terminal. */
    FAILED
}
