package com.ep.payment.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * D-240/B: 五段式对账第 5 段 — 智能体意图与指令留痕 (「AI支付」文档 ·
 * "智能体的指令与意图数据…未来争议发生时，你要回答的第一个问题不是钱对不对，
 * 而是这笔钱该不该付").
 *
 * <p>Four evidence-chain classes required by 自律公约第十八条 (关键交易节点可信
 * 存证): 用户授权 (AgentAuthorizationEntity), 模型决策 (instructionDigest +
 * decisionReason here), 支付指令 (paymentId), 风控 (riskOutcome).
 */
@Entity
@Table(name = "ep_agent_intent_log")
public class AgentIntentLogEntity {

    @Id
    @Column(name = "log_id", length = 64)
    private String logId;

    @Column(name = "authorization_id", nullable = false, length = 64)
    private String authorizationId;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "principal_user_id", nullable = false, length = 64)
    private String principalUserId;

    /** SHA-256 of the user instruction / prompt (never the raw prompt — PIPL). */
    @Column(name = "instruction_digest", nullable = false, length = 64)
    private String instructionDigest;

    /** Model's decision summary (e.g. "purchased course 42 within budget"). */
    @Column(name = "decision_reason", nullable = false, length = 512)
    private String decisionReason;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    /** Risk outcome at payment time (ALLOW / DENY / CHALLENGE). */
    @Column(name = "risk_outcome", nullable = false, length = 16)
    private String riskOutcome;

    /** SHA-256 of the reasoning chain (推理过程留痕 — PCI SSC auditability). */
    @Column(name = "reasoning_digest", length = 64)
    private String reasoningDigest;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AgentIntentLogEntity() {
    }

    public AgentIntentLogEntity(String logId, String authorizationId, String agentId,
                                String principalUserId, String instructionDigest,
                                String decisionReason, String paymentId, String riskOutcome,
                                String reasoningDigest, Instant createdAt) {
        this.logId = logId;
        this.authorizationId = authorizationId;
        this.agentId = agentId;
        this.principalUserId = principalUserId;
        this.instructionDigest = instructionDigest;
        this.decisionReason = decisionReason;
        this.paymentId = paymentId;
        this.riskOutcome = riskOutcome;
        this.reasoningDigest = reasoningDigest;
        this.createdAt = createdAt;
    }

    public String getLogId() { return logId; }
    public String getAuthorizationId() { return authorizationId; }
    public String getAgentId() { return agentId; }
    public String getPrincipalUserId() { return principalUserId; }
    public String getInstructionDigest() { return instructionDigest; }
    public String getDecisionReason() { return decisionReason; }
    public String getPaymentId() { return paymentId; }
    public String getRiskOutcome() { return riskOutcome; }
    public String getReasoningDigest() { return reasoningDigest; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentIntentLogEntity that)) return false;
        return Objects.equals(logId, that.logId);
    }

    @Override
    public int hashCode() { return Objects.hash(logId); }
}
