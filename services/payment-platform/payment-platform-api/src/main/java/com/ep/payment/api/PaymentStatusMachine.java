package com.ep.payment.api;

/**
 * D-235/A1: explicit payment state machine (「AI支付」文档 · 支付核心处理层 —
 * "生成支付单、支付处理、支付应答、异常处理" must be guarded by an explicit
 * transition table, not implicit service-layer setStatus calls).
 *
 * <pre>
 * PENDING ──▶ PROCESSING ──▶ SUCCESS ──▶ REFUNDED
 *    │             │
 *    │             └──▶ FAILED
 *    ├─(sync channel: charge completes inside pay(), e.g. balance/mock)─▶ SUCCESS
 *    └──(timeout / channel reject)──▶ FAILED
 * </pre>
 *
 * Terminal states: SUCCESS (only exit: refund), FAILED.
 */
public enum PaymentStatusMachine {

    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    REFUNDED;

    /**
     * @return true when the transition {@code from → to} is legal.
     */
    public static boolean canTransition(PaymentStatus from, PaymentStatus to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        return switch (from) {
            // PENDING→SUCCESS: synchronous channels (balance, mock, some
            // quick-pay flows) return the final status inside the pay() call.
            case PENDING -> to == PaymentStatus.PROCESSING
                    || to == PaymentStatus.SUCCESS
                    || to == PaymentStatus.FAILED;
            case PROCESSING -> to == PaymentStatus.SUCCESS || to == PaymentStatus.FAILED;
            case SUCCESS -> to == PaymentStatus.REFUNDED;
            case FAILED, REFUNDED -> false; // terminal
        };
    }

    /**
     * Guarded transition — throws when illegal. Rich-aggregate convention
     * (D-200/D-224): transitions live next to the state they protect.
     */
    public static void requireTransition(PaymentStatus from, PaymentStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    "Illegal payment status transition: " + from + " → " + to);
        }
    }
}
