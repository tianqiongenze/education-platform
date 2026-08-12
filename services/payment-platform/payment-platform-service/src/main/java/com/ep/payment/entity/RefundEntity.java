package com.ep.payment.entity;

import com.ep.payment.api.Refund;
import com.ep.payment.api.RefundStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * D-132: JPA persistence for the OPM {@link Refund} entity (SDD §3.2 —
 * {@code ep_refund}).
 * <p>
 * Replaces the in-memory {@link java.util.concurrent.ConcurrentHashMap} storage in
 * {@code PaymentServiceImpl} so refunds survive process restarts. The primary key
 * is the application-assigned {@code refundId} (a Snowflake/UUID string) — NOT an
 * AUTO_INCREMENT id, following the PingCAP best practice to avoid TiDB single-Region
 * write hotspots.
 * <p>
 * One Payment may have many Refunds (partial refunds); the Payment only transitions
 * to {@link com.ep.payment.api.PaymentStatus#REFUNDED} when the sum of successful
 * Refunds equals the Payment amount.
 */
@Entity
@Table(name = "ep_refund")
public class RefundEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Application-assigned id (UUID). D-132: NOT auto-increment — avoids the TiDB
     * write hotspot that a monotonic id would cause.
     */
    @Id
    @Column(name = "refund_id", length = 64)
    private String refundId;

    @Column(name = "payment_id", nullable = false, length = 64)
    private String paymentId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private RefundStatus status;

    @Column(length = 256)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Version
    @Column(name = "lock_version")
    private Integer lockVersion;

    public RefundEntity() {
    }

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
    public Integer getLockVersion() { return lockVersion; }
    public void setLockVersion(Integer lockVersion) { this.lockVersion = lockVersion; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefundEntity that)) return false;
        return Objects.equals(refundId, that.refundId);
    }

    @Override
    public int hashCode() { return Objects.hash(refundId); }

    @Override
    public String toString() {
        return "RefundEntity{refundId='" + refundId + "', paymentId='" + paymentId
                + "', orderId='" + orderId + "', amount=" + amount
                + ", status=" + status + ", reason='" + reason + "'}";
    }
}
