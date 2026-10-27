package com.ep.payment.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * D-240/B: agent payment authorization (「AI支付」文档 · L2 控制与授权层 —
 * "用密码学可验证的委托书，锁定范围、限额、行为者身份和许可条件").
 *
 * <p>授权协议四要素 (自律公约第十三条): 范围(scope JSON)、限额(per-tx + daily)、
 * 期限(effective from/to)、权利义务(termsVersion)。四支柱: 签协议(issue)、
 * 核意愿(principal login at issue time)、按授权执行(verifyAndReserve at pay
 * time)、可撤销(revoke API).
 *
 * <p>三种决策模式 (网信办实施意见第6条):
 * <ul>
 *   <li>{@link DecisionMode#CONFIRM_REQUIRED 模式一/二}: 笔笔确认 — every
 *       payment needs the principal's explicit confirmation (微信 "笔笔确认").</li>
 *   <li>{@link DecisionMode#PRE_AUTHORIZED}: within scope → auto-pay without
 *       per-tx confirmation.</li>
 *   <li>{@link DecisionMode#AUTONOMOUS 模式三}: low-risk, revertible,
 *       traceable only — requires the degraded-mode refund path to exist
 *       (回退机制, 自律公约配套五) and full intent logging.</li>
 * </ul>
 */
@Entity
@Table(name = "ep_agent_authorization")
public class AgentAuthorizationEntity {

    @Id
    @Column(name = "authorization_id", length = 64)
    private String authorizationId;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    /** The delegating principal (委托人). */
    @Column(name = "principal_user_id", nullable = false, length = 64)
    private String principalUserId;

    @Column(nullable = false, length = 24)
    @Enumerated(EnumType.STRING)
    private DecisionMode mode;

    /** Scope JSON — allowed channels / product categories / scenario list. */
    @Column(name = "scope_json", nullable = false, length = 1024)
    private String scopeJson;

    @Column(name = "per_tx_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal perTxLimit;

    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLimit;

    @Column(name = "daily_used", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyUsed = BigDecimal.ZERO;

    @Column(name = "daily_used_date", length = 10)
    private String dailyUsedDate;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to", nullable = false)
    private Instant effectiveTo;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private AuthStatus status;

    /** 授权协议四要素之"权利义务" — versioned terms the principal signed. */
    @Column(name = "terms_version", nullable = false, length = 16)
    private String termsVersion;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 256)
    private String revokeReason;

    @Version
    @Column(name = "lock_version")
    private Integer lockVersion;

    public enum DecisionMode { CONFIRM_REQUIRED, PRE_AUTHORIZED, AUTONOMOUS }

    public enum AuthStatus { ACTIVE, SUSPENDED, REVOKED, EXPIRED }

    public AgentAuthorizationEntity() {
    }

    public AgentAuthorizationEntity(String authorizationId, String agentId,
                                    String principalUserId, DecisionMode mode,
                                    String scopeJson, BigDecimal perTxLimit,
                                    BigDecimal dailyLimit, Instant effectiveFrom,
                                    Instant effectiveTo, String termsVersion, Instant issuedAt) {
        this.authorizationId = authorizationId;
        this.agentId = agentId;
        this.principalUserId = principalUserId;
        this.mode = mode;
        this.scopeJson = scopeJson;
        this.perTxLimit = perTxLimit;
        this.dailyLimit = dailyLimit;
        this.dailyUsed = BigDecimal.ZERO;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.termsVersion = termsVersion;
        this.status = AuthStatus.ACTIVE;
        this.issuedAt = issuedAt;
    }

    /** 授权四支柱之"按授权执行": payment-time boundary check (fail-closed). */
    public void verifyForPayment(BigDecimal amount, Instant now) {
        if (status != AuthStatus.ACTIVE) {
            throw new AgentAuthorizationException("AUTH_INACTIVE",
                    "authorization " + authorizationId + " is " + status);
        }
        if (now.isBefore(effectiveFrom) || now.isAfter(effectiveTo)) {
            throw new AgentAuthorizationException("AUTH_WINDOW",
                    "authorization " + authorizationId + " outside validity window");
        }
        if (amount.compareTo(perTxLimit) > 0) {
            throw new AgentAuthorizationException("LIMIT_PER_TX",
                    "amount " + amount + " exceeds per-tx limit " + perTxLimit);
        }
        String today = java.time.LocalDate.ofInstant(now, java.time.ZoneId.of("UTC")).toString();
        if (!today.equals(dailyUsedDate)) {
            dailyUsedDate = today;
            dailyUsed = BigDecimal.ZERO;
        }
        if (dailyUsed.add(amount).compareTo(dailyLimit) > 0) {
            throw new AgentAuthorizationException("LIMIT_DAILY",
                    "daily usage " + dailyUsed + " + " + amount + " exceeds daily limit " + dailyLimit);
        }
    }

    /**
     * Atomic reservation after the payment is accepted. The date source must be
     * the SAME clock instant that {@link #verifyForPayment} validated against —
     * deriving it from {@code Instant.now()} here would re-normalize against a
     * different day and silently zero the daily usage that verify just budgeted.
     */
    public void reserveDaily(BigDecimal amount, Instant now) {
        String today = java.time.LocalDate.ofInstant(now, java.time.ZoneId.of("UTC")).toString();
        if (!today.equals(dailyUsedDate)) {
            dailyUsedDate = today;
            dailyUsed = BigDecimal.ZERO;
        }
        dailyUsed = dailyUsed.add(amount);
    }

    /** 授权四支柱之"可撤销" — immediate, irrevocable from the client side. */
    public void revoke(String reason, Instant at) {
        if (status == AuthStatus.REVOKED) {
            return; // idempotent revoke
        }
        this.status = AuthStatus.REVOKED;
        this.revokedAt = at;
        this.revokeReason = reason;
    }

    public boolean requiresConfirmation() { return mode == DecisionMode.CONFIRM_REQUIRED; }
    public boolean allowsAutonomous() { return mode == DecisionMode.AUTONOMOUS || mode == DecisionMode.PRE_AUTHORIZED; }

    public String getAuthorizationId() { return authorizationId; }
    public String getAgentId() { return agentId; }
    public String getPrincipalUserId() { return principalUserId; }
    public DecisionMode getMode() { return mode; }
    public String getScopeJson() { return scopeJson; }
    public BigDecimal getPerTxLimit() { return perTxLimit; }
    public BigDecimal getDailyLimit() { return dailyLimit; }
    public BigDecimal getDailyUsed() { return dailyUsed; }
    public String getDailyUsedDate() { return dailyUsedDate; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Instant getEffectiveTo() { return effectiveTo; }
    public AuthStatus getStatus() { return status; }
    public String getTermsVersion() { return termsVersion; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public Integer getLockVersion() { return lockVersion; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentAuthorizationEntity that)) return false;
        return Objects.equals(authorizationId, that.authorizationId);
    }

    @Override
    public int hashCode() { return Objects.hash(authorizationId); }
}
