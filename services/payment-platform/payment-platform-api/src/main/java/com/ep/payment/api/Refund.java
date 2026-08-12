package com.ep.payment.api;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * OPM Refund entity (SDD §3.1 — "实体 Refund(退款, 一支付多退款)").
 * A {@link Payment} may have many Refunds (partial refunds); the Payment only
 * transitions to {@link PaymentStatus#REFUNDED} when the sum of successful
 * Refunds equals the Payment amount.
 *
 * <p>Plain domain entity (NOT JPA) mirroring {@code ep_refund} (SDD §3.2).
 * D-040: POJO — GraalVM AOT friendly.
 * Serializable so Dubbo can return it to remote consumers.
 */
public class Refund implements Serializable {

    /** UUID. */
    private String refundId;
    /** FK to {@link Payment#getPaymentId()}. */
    private String paymentId;
    /** FK to {@code ep_order.order_id} (denormalised for querying). */
    private String orderId;
    /** D-044 multi-tenant scope (denormalised from the Payment). */
    private String tenantId;
    private BigDecimal amount;
    private RefundStatus status;
    /** Free-text reason supplied by the caller (SDD column {@code reason VARCHAR(256)}). */
    private String reason;
    private Instant createdAt;
    /** Populated on SUCCESS. Nullable before that. */
    private Instant refundedAt;

    public Refund() {
    }

    public Refund(String refundId, String paymentId, String orderId, String tenantId,
                  BigDecimal amount, RefundStatus status, String reason, Instant createdAt) {
        this.refundId = refundId;
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.tenantId = tenantId;
        this.amount = amount;
        this.status = status;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    /** Transition PENDING → SUCCESS, recording the gateway-confirmed instant. */
    public void markSuccess(Instant refundedAt) {
        if (status != RefundStatus.PENDING) {
            throw new IllegalStateException(
                    "Refund not in PENDING status, cannot mark SUCCESS. refundId="
                            + refundId + " status=" + status);
        }
        this.refundedAt = refundedAt == null ? Instant.now() : refundedAt;
        this.status = RefundStatus.SUCCESS;
    }

    /** Transition PENDING → FAILED (gateway rejected). */
    public void markFailed() {
        if (status != RefundStatus.PENDING) {
            throw new IllegalStateException(
                    "Refund not in PENDING status, cannot mark FAILED. refundId="
                            + refundId + " status=" + status);
        }
        this.status = RefundStatus.FAILED;
    }

    // --------------------- accessors ---------------------

    public String getRefundId() { return refundId; }
    public void setRefundId(String refundId) { this.refundId = refundId; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public RefundStatus getStatus() { return status; }
    public void setStatus(RefundStatus status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getRefundedAt() { return refundedAt; }
    public void setRefundedAt(Instant refundedAt) { this.refundedAt = refundedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Refund refund)) return false;
        return Objects.equals(refundId, refund.refundId);
    }

    @Override
    public int hashCode() { return Objects.hash(refundId); }

    @Override
    public String toString() {
        return "Refund{refundId='" + refundId + "', paymentId='" + paymentId
                + "', orderId='" + orderId + "', amount=" + amount
                + ", status=" + status + ", reason='" + reason + "'}";
    }
}
