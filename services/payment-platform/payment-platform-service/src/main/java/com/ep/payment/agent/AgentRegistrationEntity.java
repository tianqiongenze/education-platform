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
 * D-240/B: KYA agent registration (「AI支付」文档 · KYA "了解你的智能体" —
 * 模型来源 / 身份标识 / 权限边界 / 接口调用 / 行为特征).
 *
 * <p>An agent is NOT a customer, NOT a user, NOT an account — it is an
 * <b>authorized user of an account</b> (the reference doc's 4th object in the
 * 三户模型). Every agent resolves to exactly one principal user (KYA: "每一笔
 * 智能体发起的支付都能找到委托人").
 */
@Entity
@Table(name = "ep_agent_registration")
public class AgentRegistrationEntity {

    @Id
    @Column(name = "agent_id", length = 64)
    private String agentId;

    /** The principal user this agent acts for (KYA requirement #3). */
    @Column(name = "owner_user_id", nullable = false, length = 64)
    private String ownerUserId;

    @Column(nullable = false, length = 128)
    private String displayName;

    /** KYA #1: model provenance (e.g. "glm-5.3-flash", "openai/gpt-5"). */
    @Column(name = "model_provider", nullable = false, length = 96)
    private String modelProvider;

    @Column(name = "model_id", nullable = false, length = 96)
    private String modelId;

    /** KYA #2: verifiable identity — a public key or platform-issued id. */
    @Column(name = "identity_key", nullable = false, length = 256)
    private String identityKey;

    /** 备案状态 (自律公约: 须使用已登记备案的智能体). */
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private RegistrationStatus status;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Version
    @Column(name = "lock_version")
    private Integer lockVersion;

    public enum RegistrationStatus { PENDING, REGISTERED, SUSPENDED }

    public AgentRegistrationEntity() {
    }

    public AgentRegistrationEntity(String agentId, String ownerUserId, String displayName,
                                   String modelProvider, String modelId, String identityKey,
                                   Instant registeredAt) {
        this.agentId = agentId;
        this.ownerUserId = ownerUserId;
        this.displayName = displayName;
        this.modelProvider = modelProvider;
        this.modelId = modelId;
        this.identityKey = identityKey;
        this.status = RegistrationStatus.PENDING;
        this.registeredAt = registeredAt;
    }

    public void register() {
        if (this.status != RegistrationStatus.PENDING) {
            throw new IllegalStateException("agent " + agentId + " is " + status + ", cannot register");
        }
        this.status = RegistrationStatus.REGISTERED;
    }

    public void suspend() { this.status = RegistrationStatus.SUSPENDED; }

    public boolean isUsable() { return status == RegistrationStatus.REGISTERED; }

    public String getAgentId() { return agentId; }
    public String getOwnerUserId() { return ownerUserId; }
    public String getDisplayName() { return displayName; }
    public String getModelProvider() { return modelProvider; }
    public String getModelId() { return modelId; }
    public String getIdentityKey() { return identityKey; }
    public RegistrationStatus getStatus() { return status; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Integer getLockVersion() { return lockVersion; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentRegistrationEntity that)) return false;
        return Objects.equals(agentId, that.agentId);
    }

    @Override
    public int hashCode() { return Objects.hash(agentId); }
}
