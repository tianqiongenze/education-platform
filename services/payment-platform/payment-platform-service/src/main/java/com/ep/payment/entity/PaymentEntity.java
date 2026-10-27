package com.ep.payment.entity;

import com.ep.payment.api.Payment;
import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentStatus;

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
 * D-132: JPA persistence for the OPM {@link Payment} aggregate (SDD §3.2 —
 * {@code ep_payment}).
 * <p>
 * Replaces the in-memory {@link java.util.concurrent.ConcurrentHashMap} storage in
 * {@code PaymentServiceImpl} so payments survive process restarts. The primary key
 * is the application-assigned {@code paymentId} (a Snowflake/UUID string) — NOT an
 * AUTO_INCREMENT id, following the PingCAP best practice to avoid TiDB single-Region
 * write hotspots.
 * <p>
 * This entity mirrors the domain {@link Payment} field shape 1:1. The service layer
 * converts between the two (the domain type is persistence-agnostic and never
 * crosses the Dubbo boundary as a JPA entity). Stored as a String column; enum
 * values are persisted by name ({@link EnumType#STRING}).
 */
@Entity
@Table(name = "ep_payment")
public class PaymentEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Application-assigned id (UUID). D-132: NOT auto-increment — avoids the TiDB
     * write hotspot that a monotonic id would cause.
     */
    @Id
    @Column(name = "payment_id", length = 64)
    private String paymentId;

    /**
     * The order this payment settles. D-090: {@code unique = true} — at most one
     * payment row per order. This is the DB-level guard that backs the
     * {@code existsByOrderId} idempotency check in {@code PaymentServiceImpl} so a
     * duplicate create request for the same order cannot mint a second payment
     * even under a race.
     */
    @Column(name = "order_id", nullable = false, length = 64, unique = true)
    private String orderId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(length = 8)
    private String currency;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private PaymentChannel channel;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(name = "channel_tx_id", length = 128)
    private String channelTxId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** D-240/B: agent provenance (五段式第5段) — null for human-initiated payments. */
    @Column(name = "agent_id", length = 64)
    private String agentId;

    /** D-240/B: the authorization that covered this agent payment. */
    @Column(name = "authorization_id", length = 64)
    private String authorizationId;

    @Version
    @Column(name = "lock_version")
    private Integer lockVersion;

    public PaymentEntity() {
    }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public PaymentChannel getChannel() { return channel; }
    public void setChannel(PaymentChannel channel) { this.channel = channel; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public String getChannelTxId() { return channelTxId; }
    public void setChannelTxId(String channelTxId) { this.channelTxId = channelTxId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public Integer getLockVersion() { return lockVersion; }
    public void setLockVersion(Integer lockVersion) { this.lockVersion = lockVersion; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getAuthorizationId() { return authorizationId; }
    public void setAuthorizationId(String authorizationId) { this.authorizationId = authorizationId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentEntity that)) return false;
        return Objects.equals(paymentId, that.paymentId);
    }

    @Override
    public int hashCode() { return Objects.hash(paymentId); }

    @Override
    public String toString() {
        return "PaymentEntity{paymentId='" + paymentId + "', orderId='" + orderId
                + "', tenantId='" + tenantId + "', amount=" + amount
                + " " + currency + ", channel=" + channel
                + ", status=" + status + ", channelTxId='" + channelTxId + "'}";
    }
}
