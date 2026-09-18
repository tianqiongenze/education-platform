package com.ep.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * D-222: durable record of a failed Payment Saga refund compensation.
 * <p>
 * The Saga refund compensation is deliberately best-effort (a thrown
 * exception would abort the LIFO compensation chain and strand the reserved
 * stock), so a failed refund must be surfaced through a durable, queryable
 * record instead. Each row represents money that the platform still owes to a
 * customer; rows stay open ({@code resolved=false}) until an operator refunds
 * out-of-band and closes them via the reconciliation endpoint.
 */
@Entity
@Table(name = "ep_payment_compensation_failure")
public class PaymentCompensationFailure implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Application-assigned UUID (low-volume table; avoids TiDB hotspot IDs). */
    @Id
    @Column(name = "failure_id", length = 36)
    private String failureId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /** Why the refund RPC failed (exception class + message, truncated). */
    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt;

    /** Open until an operator confirms the out-of-band refund. */
    @Column(name = "resolved", nullable = false)
    private boolean resolved = false;

    /** Operator note recorded when the row is resolved. */
    @Column(name = "resolved_note", length = 512)
    private String resolvedNote;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected PaymentCompensationFailure() {
        // JPA
    }

    private PaymentCompensationFailure(String failureId, String orderId, String tenantId,
                                       BigDecimal amount, String reason, Instant failedAt) {
        this.failureId = failureId;
        this.orderId = orderId;
        this.tenantId = tenantId;
        this.amount = amount;
        this.reason = reason;
        this.failedAt = failedAt;
        this.resolved = false;
    }

    public static PaymentCompensationFailure open(String failureId, String orderId, String tenantId,
                                                  BigDecimal amount, String reason, Instant failedAt) {
        return new PaymentCompensationFailure(failureId, orderId, tenantId, amount, reason, failedAt);
    }

    /**
     * Close this failure after the out-of-band refund has been confirmed.
     *
     * @throws IllegalStateException when the record is already resolved
     */
    public void resolve(String note, Instant resolvedAt) {
        if (this.resolved) {
            throw new IllegalStateException(
                    "compensation failure " + failureId + " already resolved");
        }
        this.resolved = true;
        this.resolvedNote = note;
        this.resolvedAt = resolvedAt;
    }

    public String getFailureId() {
        return failureId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public boolean isResolved() {
        return resolved;
    }

    public String getResolvedNote() {
        return resolvedNote;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
