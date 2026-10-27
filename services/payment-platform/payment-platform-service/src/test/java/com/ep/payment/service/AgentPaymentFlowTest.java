package com.ep.payment.service;

import com.ep.payment.api.AgentPaymentRejectedException;
import com.ep.payment.api.AgentPaymentRequest;
import com.ep.payment.api.Payment;
import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentStatus;
import com.ep.payment.agent.AgentAuthorizationEntity;
import com.ep.payment.agent.AgentAuthorizationException;
import com.ep.payment.agent.AgentAuthorizationService;
import com.ep.payment.channel.MockPaymentChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-240/B: end-to-end agent payment flow through {@link PaymentServiceImpl}
 * with the in-memory store — authorization envelope, 笔笔确认 token enforcement,
 * and provenance persistence.
 */
@ExtendWith(MockitoExtension.class)
class AgentPaymentFlowTest {

    @Mock
    private AgentAuthorizationService agentAuthService;
    @Mock
    private com.ep.payment.agent.PaymentTokenService tokenService;

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    private PaymentServiceImpl serviceWithEnvelope() {
        return new PaymentServiceImpl(List.of(new MockPaymentChannel()), true, null,
                agentAuthService, tokenService);
    }

    private AgentAuthorizationEntity auth(AgentAuthorizationEntity.DecisionMode mode) {
        return new AgentAuthorizationEntity("AUTH-1", "AG-1", "U-1", mode, "{}",
                new BigDecimal("100.00"), new BigDecimal("1000.00"),
                NOW.minusSeconds(3600), NOW.plusSeconds(86400), "v1", NOW.minusSeconds(7200));
    }

    private AgentPaymentRequest agentRequest(String token, String orderId) {
        return new AgentPaymentRequest(orderId, "tenant-A", PaymentChannel.MOCK,
                new BigDecimal("60.00"), "CNY", "AG-1", "AUTH-1", token);
    }

    @Test
    @DisplayName("PRE_AUTHORIZED: verified agent payment succeeds and records provenance")
    void happyPath() {
        when(agentAuthService.verifyAndReserve(eq("AG-1"), eq("AUTH-1"),
                eq(new BigDecimal("60.00")), any(Instant.class)))
                .thenReturn(auth(AgentAuthorizationEntity.DecisionMode.PRE_AUTHORIZED));
        PaymentServiceImpl svc = serviceWithEnvelope();
        PaymentResult result = svc.createAgentPayment(agentRequest(null, "o-agent-1"));
        assertEquals(PaymentStatus.SUCCESS, result.status());
        Payment stored = svc.getPayment(result.paymentId());
        assertEquals("AG-1", stored.getAgentId());
        assertEquals("AUTH-1", stored.getAuthorizationId());
        verify(tokenService, never()).consume(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("CONFIRM_REQUIRED without token → TOKEN_REQUIRED, nothing charged")
    void confirmRequiredNeedsToken() {
        when(agentAuthService.verifyAndReserve(anyString(), anyString(), any(), any(Instant.class)))
                .thenReturn(auth(AgentAuthorizationEntity.DecisionMode.CONFIRM_REQUIRED));
        AgentPaymentRejectedException e = assertThrows(AgentPaymentRejectedException.class,
                () -> serviceWithEnvelope().createAgentPayment(agentRequest(null, "o-agent-2")));
        assertEquals("TOKEN_REQUIRED", e.getCode());
    }

    @Test
    @DisplayName("CONFIRM_REQUIRED with token: token consumed bound to the payment")
    void confirmRequiredWithToken() {
        when(agentAuthService.verifyAndReserve(anyString(), anyString(), any(), any(Instant.class)))
                .thenReturn(auth(AgentAuthorizationEntity.DecisionMode.CONFIRM_REQUIRED));
        when(tokenService.consume(anyString(), anyString(), any(), any(Instant.class)))
                .thenAnswer(inv -> null);
        PaymentServiceImpl svc = serviceWithEnvelope();
        PaymentResult result = svc.createAgentPayment(agentRequest("TOK-1", "o-agent-3"));
        assertEquals(PaymentStatus.SUCCESS, result.status());
        verify(tokenService).consume(eq("TOK-1"), eq(result.paymentId()),
                eq(new BigDecimal("60.00")), any(Instant.class));
    }

    @Test
    @DisplayName("authorization failure codes propagate verbatim (fail-closed)")
    void authorizationCodesPropagate() {
        when(agentAuthService.verifyAndReserve(anyString(), anyString(), any(), any(Instant.class)))
                .thenThrow(new AgentAuthorizationException("LIMIT_DAILY",
                        "daily usage exhausted"));
        AgentPaymentRejectedException e = assertThrows(AgentPaymentRejectedException.class,
                () -> serviceWithEnvelope().createAgentPayment(agentRequest(null, "o-agent-4")));
        assertEquals("LIMIT_DAILY", e.getCode());
    }

    @Test
    @DisplayName("agent mismatch rejected before any charge")
    void agentMismatchRejected() {
        when(agentAuthService.verifyAndReserve(anyString(), anyString(), any(), any(Instant.class)))
                .thenThrow(new AgentAuthorizationException("AGENT_MISMATCH", "not your auth"));
        AgentPaymentRejectedException e = assertThrows(AgentPaymentRejectedException.class,
                () -> serviceWithEnvelope().createAgentPayment(agentRequest(null, "o-agent-5")));
        assertEquals("AGENT_MISMATCH", e.getCode());
    }

    @Test
    @DisplayName("token replay → TOKEN_REPLAYED, no payment minted")
    void tokenReplayRejected() {
        when(agentAuthService.verifyAndReserve(anyString(), anyString(), any(), any(Instant.class)))
                .thenReturn(auth(AgentAuthorizationEntity.DecisionMode.CONFIRM_REQUIRED));
        when(tokenService.consume(anyString(), anyString(), any(), any(Instant.class)))
                .thenThrow(new AgentAuthorizationException("TOKEN_REPLAYED", "already used"));
        PaymentServiceImpl svc = serviceWithEnvelope();
        AgentPaymentRejectedException e = assertThrows(AgentPaymentRejectedException.class,
                () -> svc.createAgentPayment(agentRequest("TOK-REPLAY", "o-agent-6")));
        assertEquals("TOKEN_REPLAYED", e.getCode());
        assertNull(svc.getPaymentByOrder("o-agent-6"));
    }

    @Test
    @DisplayName("envelope absent → agent payment refused outright")
    void envelopeAbsentRefuses() {
        PaymentServiceImpl svc = new PaymentServiceImpl(List.of(new MockPaymentChannel()), true);
        AgentPaymentRejectedException e = assertThrows(AgentPaymentRejectedException.class,
                () -> svc.createAgentPayment(agentRequest(null, "o-agent-7")));
        assertEquals("ENVELOPE_ABSENT", e.getCode());
    }

    @Test
    @DisplayName("human path unaffected: no agent provenance on plain payments")
    void humanPathUnchanged() {
        PaymentServiceImpl svc = serviceWithEnvelope();
        PaymentResult result = svc.createPayment(new com.ep.payment.api.PaymentRequest(
                "o-human-1", "tenant-A", PaymentChannel.MOCK,
                new BigDecimal("30.00"), "CNY"));
        assertEquals(PaymentStatus.SUCCESS, result.status());
        assertNull(svc.getPayment(result.paymentId()).getAgentId());
        verify(agentAuthService, never()).verifyAndReserve(
                anyString(), anyString(), any(), any(Instant.class));
        verify(agentAuthService, never()).logIntent(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), isNull(), any(Instant.class));
    }
}
