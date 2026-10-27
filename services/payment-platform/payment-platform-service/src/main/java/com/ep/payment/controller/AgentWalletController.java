package com.ep.payment.controller;

import com.ep.payment.agent.AgentAuthorizationEntity;
import com.ep.payment.agent.AgentAuthorizationService;
import com.ep.payment.agent.AgentRegistrationEntity;
import com.ep.payment.agent.AgentIntentLogEntity;
import com.ep.payment.agent.PaymentTokenEntity;
import com.ep.payment.agent.PaymentTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * D-240/B: AI 钱包控制台 (「AI支付」· "可撤销的 AI 钱包" + 四支柱). The principal's
 * HTTP surface for everything around agent payments:
 *
 * <ul>
 *   <li>KYA registration — register an agent and bind it to the principal.</li>
 *   <li>Authorization lifecycle — issue (四要素), list, REVOKE (支柱四, kill switch).</li>
 *   <li>笔笔确认 — confirm a pending decision and mint the one-time payment token.</li>
 *   <li>Auditability — daily usage and the intent evidence trail (五段式第5段).</li>
 * </ul>
 *
 * <p>The agent itself never talks to this controller: it presents the
 * authorizationId (and token, when required) to
 * {@code PaymentService#createAgentPayment} over Dubbo. All mutations here are
 * principal-initiated.
 */
@RestController
@RequestMapping("/api/agents")
@Validated
public class AgentWalletController {

    private final AgentAuthorizationService agentAuthorizationService;
    private final PaymentTokenService paymentTokenService;

    public AgentWalletController(AgentAuthorizationService agentAuthorizationService,
                                 PaymentTokenService paymentTokenService) {
        this.agentAuthorizationService = agentAuthorizationService;
        this.paymentTokenService = paymentTokenService;
    }

    // ------------------------------ KYA ------------------------------

    /** Register an agent (KYA: model provenance + identity key) and bind it to the principal. */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterHttp body) {
        AgentRegistrationEntity reg = agentAuthorizationService.register(
                body.agentId(), body.principalUserId(), body.displayName(),
                body.modelProvider(), body.modelId(), body.identityKey(), Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "agentId", reg.getAgentId(),
                "ownerUserId", reg.getOwnerUserId(),
                "status", reg.getStatus().name()));
    }

    // ------------------------- authorization -------------------------

    /** 授权支柱一"签协议": issue an authorization with the 四要素. */
    @PostMapping("/authorizations")
    public ResponseEntity<Map<String, Object>> issue(@Valid @RequestBody IssueHttp body) {
        AgentAuthorizationEntity auth = agentAuthorizationService.issue(
                body.agentId(), body.principalUserId(),
                AgentAuthorizationEntity.DecisionMode.valueOf(body.mode().trim().toUpperCase()),
                body.scopeJson() == null ? "{}" : body.scopeJson(),
                new BigDecimal(body.perTxLimit()), new BigDecimal(body.dailyLimit()),
                Instant.parse(body.effectiveFrom()), Instant.parse(body.effectiveTo()),
                body.termsVersion() == null ? "v1" : body.termsVersion(), Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(auth));
    }

    /** AI 钱包控制台 listing — every authorization the principal has signed. */
    @GetMapping("/authorizations")
    public List<Map<String, Object>> list(@RequestParam("principalUserId") String principalUserId) {
        return agentAuthorizationService.listForPrincipal(principalUserId).stream()
                .map(AgentWalletController::view)
                .toList();
    }

    /** 授权支柱四"可撤销": the kill switch. Only the principal may revoke. */
    @PostMapping("/authorizations/{authorizationId}/revoke")
    public ResponseEntity<Map<String, Object>> revoke(
            @PathVariable String authorizationId, @Valid @RequestBody RevokeHttp body) {
        AgentAuthorizationEntity auth = agentAuthorizationService.revoke(
                authorizationId, body.principalUserId(),
                body.reason() == null ? "wallet console revoke" : body.reason(), Instant.now());
        return ResponseEntity.ok(Map.of(
                "authorizationId", auth.getAuthorizationId(),
                "status", auth.getStatus().name(),
                "revokedAt", String.valueOf(auth.getRevokedAt())));
    }

    /** Daily-usage view (限额与日累计) for the wallet console. */
    @GetMapping("/authorizations/{authorizationId}/usage")
    public ResponseEntity<Map<String, Object>> usage(@PathVariable String authorizationId) {
        return agentAuthorizationService.getAuthorization(authorizationId)
                .<ResponseEntity<Map<String, Object>>>map(auth ->
                        ResponseEntity.ok(view(auth)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "error", "authorization " + authorizationId + " not found")));
    }

    // --------------------------- 笔笔确认 ---------------------------

    /**
     * Confirm a CONFIRM_REQUIRED purchase: mints a one-time payment token whose
     * ceiling is the confirmed amount. The agent presents this token at
     * {@code createAgentPayment}; it is consumed atomically.
     */
    @PostMapping("/authorizations/{authorizationId}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String authorizationId, @Valid @RequestBody ConfirmHttp body) {
        PaymentTokenEntity token = paymentTokenService.issue(
                authorizationId, body.principalUserId(),
                new BigDecimal(body.confirmedAmount()), Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "tokenId", token.getTokenId(),
                "authorizationId", token.getAuthorizationId(),
                "maxAmount", token.getMaxAmount(),
                "expiresAt", token.getExpiresAt().toString()));
    }

    // ------------------------ evidence trail ------------------------

    /** 五段式第5段: the intent evidence trail for one authorization. */
    @GetMapping("/authorizations/{authorizationId}/intents")
    public List<Map<String, Object>> intents(@PathVariable String authorizationId) {
        List<AgentIntentLogEntity> trail = agentAuthorizationService.intentTrail(authorizationId);
        return trail.stream().map(l -> {
            Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put("logId", l.getLogId());
            m.put("agentId", l.getAgentId());
            m.put("paymentId", l.getPaymentId() == null ? "" : l.getPaymentId());
            m.put("riskOutcome", l.getRiskOutcome());
            m.put("decisionReason", l.getDecisionReason());
            m.put("instructionDigest", l.getInstructionDigest());
            m.put("createdAt", l.getCreatedAt().toString());
            return m;
        }).toList();
    }

    // ------------------------------ view ------------------------------

    private static Map<String, Object> view(AgentAuthorizationEntity a) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("authorizationId", a.getAuthorizationId());
        m.put("agentId", a.getAgentId());
        m.put("principalUserId", a.getPrincipalUserId());
        m.put("mode", a.getMode().name());
        m.put("perTxLimit", a.getPerTxLimit());
        m.put("dailyLimit", a.getDailyLimit());
        m.put("dailyUsed", a.getDailyUsed());
        m.put("effectiveFrom", a.getEffectiveFrom().toString());
        m.put("effectiveTo", a.getEffectiveTo().toString());
        m.put("status", a.getStatus().name());
        m.put("termsVersion", a.getTermsVersion());
        return m;
    }

    // --------------------------- HTTP bodies ---------------------------

    public record RegisterHttp(
            @NotBlank String agentId,
            @NotBlank String principalUserId,
            @NotBlank String displayName,
            @NotBlank String modelProvider,
            @NotBlank String modelId,
            @NotBlank String identityKey) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }

    public record IssueHttp(
            @NotBlank String agentId,
            @NotBlank String principalUserId,
            @NotBlank String mode,
            String scopeJson,
            @NotBlank String perTxLimit,
            @NotBlank String dailyLimit,
            @NotBlank String effectiveFrom,
            @NotBlank String effectiveTo,
            String termsVersion) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }

    public record RevokeHttp(@NotBlank String principalUserId, String reason)
            implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }

    public record ConfirmHttp(@NotBlank String principalUserId, @NotBlank String confirmedAmount)
            implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }
}
