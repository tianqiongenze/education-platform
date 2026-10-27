package com.ep.payment.agent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * D-240/B: agent authorization service — issues, verifies, reserves, revokes
 * and lists agent payment authorizations (「AI支付」文档 · 授权四支柱).
 *
 * <p>Fail-closed: every check that cannot be completed throws — a missing
 * registration, a suspended agent, or an over-limit amount never silently
 * degrades to an allowed payment.
 */
@Service
public class AgentAuthorizationService {

    private final AgentRegistrationRepository registrationRepository;
    private final AgentAuthorizationRepository authorizationRepository;
    private final AgentIntentLogRepository intentLogRepository;

    public AgentAuthorizationService(AgentRegistrationRepository registrationRepository,
                                     AgentAuthorizationRepository authorizationRepository,
                                     AgentIntentLogRepository intentLogRepository) {
        this.registrationRepository = registrationRepository;
        this.authorizationRepository = authorizationRepository;
        this.intentLogRepository = intentLogRepository;
    }

    // ------------------------- KYA registration -------------------------

    @Transactional
    public AgentRegistrationEntity register(String agentId, String ownerUserId,
                                            String displayName, String modelProvider,
                                            String modelId, String identityKey, Instant at) {
        // Idempotent re-register: an existing agent for the same principal is
        // returned as-is (AI 钱包控制台 re-submits are common); a DIFFERENT
        // principal claiming the same agentId is refused (KYA identity theft).
        AgentRegistrationEntity existing = registrationRepository.findByAgentId(agentId)
                .orElse(null);
        if (existing != null) {
            if (!existing.getOwnerUserId().equals(ownerUserId)) {
                throw new AgentAuthorizationException(
                        "AGENT_TAKEN", "agent " + agentId + " already belongs to another principal");
            }
            return existing;
        }
        AgentRegistrationEntity reg = new AgentRegistrationEntity(
                agentId, ownerUserId, displayName, modelProvider, modelId, identityKey, at);
        AgentRegistrationEntity saved = registrationRepository.save(reg);
        saved.register();
        return registrationRepository.save(saved);
    }

    public AgentRegistrationEntity requireRegistered(String agentId) {
        AgentRegistrationEntity reg = registrationRepository.findByAgentId(agentId)
                .orElseThrow(() -> new AgentAuthorizationException(
                        "KYA_UNREGISTERED", "KYA: agent " + agentId + " not registered"));
        if (!reg.isUsable()) {
            throw new AgentAuthorizationException(
                        "KYA_NOT_USABLE", "KYA: agent " + agentId + " is " + reg.getStatus() + " — not usable");
        }
        return reg;
    }

    // ------------------------- authorization lifecycle -------------------------

    /** 授权支柱一"签协议": issues an authorization with the 四要素. */
    @Transactional
    public AgentAuthorizationEntity issue(String agentId, String principalUserId,
                                          AgentAuthorizationEntity.DecisionMode mode,
                                          String scopeJson, BigDecimal perTxLimit,
                                          BigDecimal dailyLimit, Instant effectiveFrom,
                                          Instant effectiveTo, String termsVersion, Instant at) {
        AgentRegistrationEntity reg = requireRegistered(agentId);
        if (!reg.getOwnerUserId().equals(principalUserId)) {
            throw new AgentAuthorizationException(
                        "AGENT_NOT_OWNED", "KYA: agent " + agentId + " does not belong to principal " + principalUserId);
        }
        AgentAuthorizationEntity auth = new AgentAuthorizationEntity(
                "A" + UUID.randomUUID().toString().replace("-", ""),
                agentId, principalUserId, mode, scopeJson, perTxLimit, dailyLimit,
                effectiveFrom, effectiveTo, termsVersion, at);
        return authorizationRepository.save(auth);
    }

    /** 授权支柱三"按授权执行": payment-time boundary verification (fail-closed). */
    @Transactional
    public AgentAuthorizationEntity verifyAndReserve(String authorizationId,
                                                     BigDecimal amount, Instant now) {
        return verifyAndReserve(null, authorizationId, amount, now);
    }

    /**
     * Same as above with an agent-binding check: the presenting agent must be
     * the one the authorization was issued to (checked BEFORE any budget is
     * reserved, so a mismatched presentation consumes nothing).
     */
    @Transactional
    public AgentAuthorizationEntity verifyAndReserve(String agentId, String authorizationId,
                                                     BigDecimal amount, Instant now) {
        AgentAuthorizationEntity auth = authorizationRepository.findByAuthorizationId(authorizationId)
                .orElseThrow(() -> new AgentAuthorizationException(
                        "AUTH_NOT_FOUND", "authorization " + authorizationId + " not found"));
        if (agentId != null && !auth.getAgentId().equals(agentId)) {
            throw new AgentAuthorizationException(
                    "AGENT_MISMATCH", "authorization " + authorizationId
                    + " was issued to agent " + auth.getAgentId() + ", not " + agentId);
        }
        auth.verifyForPayment(amount, now);
        auth.reserveDaily(amount, now);
        return authorizationRepository.save(auth);
    }

    /** 授权支柱四"可撤销": immediate revoke — the AI 钱包控制台's kill switch. */
    @Transactional
    public AgentAuthorizationEntity revoke(String authorizationId, String principalUserId,
                                           String reason, Instant at) {
        AgentAuthorizationEntity auth = authorizationRepository.findByAuthorizationId(authorizationId)
                .orElseThrow(() -> new AgentAuthorizationException(
                        "AUTH_NOT_FOUND", "authorization " + authorizationId + " not found"));
        if (!auth.getPrincipalUserId().equals(principalUserId)) {
            throw new AgentAuthorizationException(
                        "NOT_PRINCIPAL", "only the principal may revoke authorization " + authorizationId);
        }
        auth.revoke(reason, at);
        return authorizationRepository.save(auth);
    }

    /** AI 钱包控制台: list authorizations for one principal. */
    @Transactional(readOnly = true)
    public List<AgentAuthorizationEntity> listForPrincipal(String principalUserId) {
        return authorizationRepository.findByPrincipalUserId(principalUserId);
    }

    /** AI 钱包控制台: fetch one authorization by id (404-safe via Optional). */
    @Transactional(readOnly = true)
    public Optional<AgentAuthorizationEntity> getAuthorization(String authorizationId) {
        return authorizationRepository.findByAuthorizationId(authorizationId);
    }

    // ------------------------- intent evidence chain -------------------------

    /** 五段式第5段: intent/instruction log (sha256 digests, never raw prompts). */
    @Transactional
    public AgentIntentLogEntity logIntent(String authorizationId, String agentId,
                                          String principalUserId, String rawInstruction,
                                          String decisionReason, String paymentId,
                                          String riskOutcome, String reasoningChain, Instant at) {
        AgentIntentLogEntity log = new AgentIntentLogEntity(
                "L" + UUID.randomUUID().toString().replace("-", ""),
                authorizationId, agentId, principalUserId,
                sha256(rawInstruction), decisionReason, paymentId, riskOutcome,
                reasoningChain == null ? null : sha256(reasoningChain), at);
        return intentLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public List<AgentIntentLogEntity> intentTrail(String authorizationId) {
        return intentLogRepository.findByAuthorizationIdOrderByCreatedAtDesc(authorizationId);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input == null ? new byte[0] : input.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
