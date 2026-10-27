package com.ep.payment.agent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * D-240/B: payment tokenization (「AI支付」文档 · 支付标记化).
 *
 * <p>For {@code CONFIRM_REQUIRED} (笔笔确认) authorizations the principal
 * confirms each purchase in the AI 钱包控制台; the console issues a one-time
 * token whose ceiling is the confirmed amount. The agent's payment call must
 * present a valid token — consuming it atomically via a conditional UPDATE, so
 * the same token can never pay twice even under a concurrent replay.
 */
@Service
public class PaymentTokenService {

    /** Default validity window for a confirmation token (笔笔确认 = short-lived). */
    static final long DEFAULT_TTL_SECONDS = 600;

    private final PaymentTokenRepository tokenRepository;
    private final AgentAuthorizationRepository authorizationRepository;

    public PaymentTokenService(PaymentTokenRepository tokenRepository,
                               AgentAuthorizationRepository authorizationRepository) {
        this.tokenRepository = tokenRepository;
        this.authorizationRepository = authorizationRepository;
    }

    /**
     * Issue a one-time token after a principal confirmation. The authorization
     * must exist, belong to the principal and be ACTIVE — fail-closed.
     */
    @Transactional
    public PaymentTokenEntity issue(String authorizationId, String principalUserId,
                                    BigDecimal confirmedAmount, Instant now) {
        AgentAuthorizationEntity auth = authorizationRepository.findByAuthorizationId(authorizationId)
                .orElseThrow(() -> new AgentAuthorizationException(
                        "AUTH_NOT_FOUND", "authorization " + authorizationId + " not found"));
        if (!auth.getPrincipalUserId().equals(principalUserId)) {
            throw new AgentAuthorizationException(
                    "NOT_PRINCIPAL", "only the principal may confirm authorization " + authorizationId);
        }
        if (auth.getStatus() != AgentAuthorizationEntity.AuthStatus.ACTIVE) {
            throw new AgentAuthorizationException(
                    "AUTH_INACTIVE", "authorization " + authorizationId + " is " + auth.getStatus());
        }
        if (confirmedAmount == null || confirmedAmount.signum() <= 0
                || confirmedAmount.compareTo(auth.getPerTxLimit()) > 0) {
            throw new IllegalArgumentException(
                    "confirmed amount must be > 0 and within the per-tx limit");
        }
        PaymentTokenEntity token = new PaymentTokenEntity(
                "T" + UUID.randomUUID().toString().replace("-", ""),
                authorizationId, principalUserId, confirmedAmount, now,
                now.plusSeconds(DEFAULT_TTL_SECONDS));
        return tokenRepository.save(token);
    }

    /**
     * Consume a token exactly once, binding it to the paymentId. Returns the
     * token on success; throws when the token is unknown, already used or its
     * ceiling is lower than the amount being charged.
     */
    @Transactional
    public PaymentTokenEntity consume(String tokenId, String paymentId,
                                      BigDecimal amount, Instant now) {
        PaymentTokenEntity token = tokenRepository.findById(tokenId)
                .orElseThrow(() -> new AgentAuthorizationException(
                        "TOKEN_INVALID", "payment token not found"));
        if (token.isExpired(now)) {
            throw new AgentAuthorizationException(
                    "TOKEN_EXPIRED", "payment token expired");
        }
        if (amount.compareTo(token.getMaxAmount()) > 0) {
            throw new AgentAuthorizationException(
                    "TOKEN_CEILING", "amount " + amount + " exceeds token ceiling " + token.getMaxAmount());
        }
        int won = tokenRepository.consume(tokenId, paymentId);
        if (won == 0) {
            throw new AgentAuthorizationException(
                    "TOKEN_REPLAYED", "payment token already used");
        }
        token.setStatus(PaymentTokenEntity.TokenStatus.CONSUMED);
        token.setPaymentId(paymentId);
        token.setConsumedAt(now);
        return token;
    }

    @Transactional(readOnly = true)
    public List<PaymentTokenEntity> tokensForAuthorization(String authorizationId) {
        return tokenRepository.findByAuthorizationId(authorizationId);
    }
}
