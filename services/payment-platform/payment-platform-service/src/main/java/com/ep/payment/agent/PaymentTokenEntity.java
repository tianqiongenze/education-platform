package com.ep.payment.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * D-240/B: one-time payment token (「AI支付」文档 · 支付标记化 — "用一次性 token
 * 代替真实支付要素"). Issued by the AI 钱包控制台 when the principal confirms a
 * {@code CONFIRM_REQUIRED} decision; consumed exactly once by the agent's
 * payment call.
 *
 * <p>Single-use is enforced at the DB level by the conditional UPDATE in
 * {@link PaymentTokenRepository#consume(String, String)} — two concurrent
 * consumers race on the {@code status='ISSUED'} predicate and only one wins.
 */
@Entity
@Table(name = "ep_payment_token")
public class PaymentTokenEntity {

    @Id
    @Column(name = "token_id", length = 64)
    private String tokenId;

    @Column(name = "authorization_id", nullable = false, length = 64)
    private String authorizationId;

    @Column(name = "principal_user_id", nullable = false, length = 64)
    private String principalUserId;

    /** Ceiling bound to the token at issue time (标记化: token ≠ 无限授权). */
    @Column(name = "max_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal maxAmount;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private TokenStatus status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    public enum TokenStatus { ISSUED, CONSUMED, EXPIRED }

    public PaymentTokenEntity() {
    }

    public PaymentTokenEntity(String tokenId, String authorizationId, String principalUserId,
                              BigDecimal maxAmount, Instant issuedAt, Instant expiresAt) {
        this.tokenId = tokenId;
        this.authorizationId = authorizationId;
        this.principalUserId = principalUserId;
        this.maxAmount = maxAmount;
        this.status = TokenStatus.ISSUED;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) { return now.isAfter(expiresAt); }

    public String getTokenId() { return tokenId; }
    public String getAuthorizationId() { return authorizationId; }
    public String getPrincipalUserId() { return principalUserId; }
    public BigDecimal getMaxAmount() { return maxAmount; }
    public TokenStatus getStatus() { return status; }
    public void setStatus(TokenStatus status) { this.status = status; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentTokenEntity that)) return false;
        return Objects.equals(tokenId, that.tokenId);
    }

    @Override
    public int hashCode() { return Objects.hash(tokenId); }
}
