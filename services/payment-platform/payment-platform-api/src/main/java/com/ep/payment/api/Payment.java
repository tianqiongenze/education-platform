package com.ep.payment.api;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * OPM Payment aggregate root (SDD §3.1 — "实体 Payment(支付, 一订单一支付)").
 *
 * <p>A plain domain entity (NOT a JPA {@code @Entity}) mirroring the shape of
 * the Order entity: behaviour-rich, persistence-agnostic. The persistence layer
 * (when wired) maps it onto {@code ep_payment} (SDD §3.2). For dev/CI the
 * {@code PaymentServiceImpl} keeps instances in a
 * {@link java.util.concurrent.ConcurrentHashMap}; production swaps in TiDB.
 *
 * <p>Multi-tenant (D-044): {@code tenantId} scopes every Payment so the same
 * store can host more than one tenant without cross-tenant leakage.
 *
 * <p>D-039: thread-safety of the <em>store</em> is the service's job; this entity
 * is single-threaded by contract (loaded, mutated, saved within one request).
 * D-040: POJO with no reflection tricks — GraalVM AOT friendly.
 * Serializable so Dubbo can return it to remote consumers.
 * Idempotency: {@code paymentId} is a caller-supplied UUID; the service
 * generates one when the caller does not.
 */
public class Payment implements Serializable {

    /** UUID. */
    private String paymentId;
    /** FK to {@code ep_order.order_id}. */
    private String orderId;
    /** D-044 multi-tenant scope. */
    private String tenantId;
    private BigDecimal amount;
    /** ISO-4217, defaults to "CNY". */
    private String currency;
    private PaymentChannel channel;
    private PaymentStatus status;
    /** Third-party transaction id, populated on SUCCESS. Nullable before that. */
    private String channelTxId;
    private Instant createdAt;
    /** Populated on SUCCESS. Nullable before that. */
    private Instant paidAt;
    /** Optimistic lock version (CAS), mirrors {@code version} on ep_payment. */
    private Integer lockVersion;

    public Payment() {
    }

    public Payment(String paymentId, String orderId, String tenantId, BigDecimal amount, String currency,
                   PaymentChannel channel, PaymentStatus status, Instant createdAt) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.tenantId = tenantId;
        this.amount = amount;
        this.currency = currency;
        this.channel = channel;
        this.status = status;
        this.createdAt = createdAt;
        this.lockVersion = 0;
    }

    // --------------------- Domain behaviour (state machine) ---------------------

    /**
     * Transition PENDING|PROCESSING → SUCCESS, recording the gateway's
     * transaction id and the confirmed paid-at instant.
     *
     * @throws IllegalStateException if the Payment is not in a payable state.
     */
    public void markSuccess(String channelTxId, Instant paidAt) {
        // D-235/A1: single source of truth for the transition table.
        PaymentStatusMachine.requireTransition(status, PaymentStatus.SUCCESS);
        if (channelTxId == null || channelTxId.isBlank()) {
            throw new IllegalArgumentException("channelTxId must not be blank on success");
        }
        this.channelTxId = channelTxId;
        this.paidAt = paidAt == null ? Instant.now() : paidAt;
        this.status = PaymentStatus.SUCCESS;
    }

    /**
     * Transition PENDING|PROCESSING → FAILED (gateway rejected / timed out).
     *
     * @throws IllegalStateException if the Payment is already terminal.
     */
    public void markFailed() {
        PaymentStatusMachine.requireTransition(status, PaymentStatus.FAILED);
        this.status = PaymentStatus.FAILED;
    }

    /**
     * Begin processing (PENDING → PROCESSING). Allowed to be a no-op when the
     * channel is synchronous (e.g. Mock).
     */
    public void beginProcessing() {
        PaymentStatusMachine.requireTransition(status, PaymentStatus.PROCESSING);
        this.status = PaymentStatus.PROCESSING;
    }

    /** Refund is only allowed from SUCCESS (SDD state machine). */
    public boolean canRefund() {
        return status == PaymentStatus.SUCCESS;
    }

    /**
     * Mark the Payment REFUNDED once the sum of successful Refunds equals the
     * Payment amount (full refund). Partial refunds leave the Payment in SUCCESS
     * so further refunds are possible.
     */
    public void markRefunded() {
        PaymentStatusMachine.requireTransition(status, PaymentStatus.REFUNDED);
        this.status = PaymentStatus.REFUNDED;
    }

    /**
     * D-240/B: agent provenance (五段式第5段) — who actually initiated this
     * charge. Null for human-initiated payments; set for agent-driven ones
     * after the L2 authorization envelope verified the charge.
     */
    private String agentId;
    private String authorizationId;

    // --------------------- accessors ---------------------

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    /** D-240/B: agent provenance (null for human payments). */
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    /** D-240/B: the authorization that covered this agent payment (null for human payments). */
    public String getAuthorizationId() { return authorizationId; }
    public void setAuthorizationId(String authorizationId) { this.authorizationId = authorizationId; }
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Payment payment)) return false;
        return Objects.equals(paymentId, payment.paymentId);
    }

    @Override
    public int hashCode() { return Objects.hash(paymentId); }

    @Override
    public String toString() {
        return "Payment{paymentId='" + paymentId + "', orderId='" + orderId
                + "', tenantId='" + tenantId + "', amount=" + amount
                + " " + currency + ", channel=" + channel
                + ", status=" + status + ", channelTxId='" + channelTxId + "'}";
    }
}
