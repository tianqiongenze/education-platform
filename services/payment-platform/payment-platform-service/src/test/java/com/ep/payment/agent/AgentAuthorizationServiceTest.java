package com.ep.payment.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * D-240/B: agent authorization boundary tests — the fail-closed matrix
 * (unregistered/suspended agent, over-limit, expired, revoked, daily budget,
 * ownership, evidence digests).
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AgentAuthorizationServiceTest {

    @Mock
    private AgentRegistrationRepository registrationRepository;
    @Mock
    private AgentAuthorizationRepository authorizationRepository;
    @Mock
    private AgentIntentLogRepository intentLogRepository;

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static final Instant YESTERDAY = NOW.minusSeconds(86400);
    private static final Instant NEXT_WEEK = NOW.plusSeconds(604800);

    private AgentAuthorizationService service() {
        return new AgentAuthorizationService(registrationRepository, authorizationRepository,
                intentLogRepository);
    }

    private void stubSaveRepos() {
        when(registrationRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(authorizationRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentRegistrationEntity registeredAgent(String agentId, String owner) {
        AgentRegistrationEntity reg = new AgentRegistrationEntity(agentId, owner, "Test Agent",
                "zai", "glm-5.3-flash", "key-" + agentId, YESTERDAY);
        reg.register(); // REGISTERED — the only KYA-usable state
        return reg;
    }

    private AgentAuthorizationEntity auth(String mode, String perTx, String daily) {
        return new AgentAuthorizationEntity("A1", "AG1", "U1",
                AgentAuthorizationEntity.DecisionMode.valueOf(mode), "{}",
                new BigDecimal(perTx), new BigDecimal(daily), YESTERDAY, NEXT_WEEK, "v1", YESTERDAY);
    }

    @Test
    @DisplayName("register transitions PENDING → REGISTERED")
    void registerFlow() {
        stubSaveRepos();
        AgentRegistrationEntity reg = service().register("AG9", "U1", "A",
                "zai", "glm", "key", NOW);
        assertEquals(AgentRegistrationEntity.RegistrationStatus.REGISTERED, reg.getStatus());
    }

    @Test
    @DisplayName("unregistered agent fails KYA (fail-closed)")
    void unregisteredFails() {
        when(registrationRepository.findByAgentId("AG-X")).thenReturn(Optional.empty());
        assertThrows(AgentAuthorizationException.class, () ->
                service().requireRegistered("AG-X"));
    }

    @Test
    @DisplayName("suspended agent fails KYA (fail-closed)")
    void suspendedFails() {
        AgentRegistrationEntity reg = registeredAgent("AG1", "U1");
        reg.suspend();
        when(registrationRepository.findByAgentId("AG1")).thenReturn(Optional.of(reg));
        assertThrows(AgentAuthorizationException.class, () ->
                service().requireRegistered("AG1"));
    }

    @Test
    @DisplayName("issue rejects agent not owned by principal (KYA #3)")
    void issueRejectsWrongOwner() {
        when(registrationRepository.findByAgentId("AG1"))
                .thenReturn(Optional.of(registeredAgent("AG1", "someone-else")));
        assertThrows(AgentAuthorizationException.class, () ->
                service().issue("AG1", "U1", AgentAuthorizationEntity.DecisionMode.PRE_AUTHORIZED,
                        "{}", new BigDecimal("100"), new BigDecimal("1000"),
                        YESTERDAY, NEXT_WEEK, "v1", NOW));
    }

    @Test
    @DisplayName("issue signs an ACTIVE authorization for a registered, owned agent")
    void issueHappyPath() {
        stubSaveRepos();
        when(registrationRepository.findByAgentId("AG1"))
                .thenReturn(Optional.of(registeredAgent("AG1", "U1")));
        AgentAuthorizationEntity a = service().issue("AG1", "U1",
                AgentAuthorizationEntity.DecisionMode.PRE_AUTHORIZED, "{}",
                new BigDecimal("100"), new BigDecimal("1000"), YESTERDAY, NEXT_WEEK, "v1", NOW);
        assertEquals(AgentAuthorizationEntity.AuthStatus.ACTIVE, a.getStatus());
        assertEquals("AG1", a.getAgentId());
        assertEquals("U1", a.getPrincipalUserId());
    }

    @Test
    @DisplayName("verify: within limits passes and reserves daily usage")
    void verifyPassesAndReserves() {
        AgentAuthorizationEntity a = auth("PRE_AUTHORIZED", "100", "1000");
        when(authorizationRepository.findByAuthorizationId("A1")).thenReturn(Optional.of(a));
        when(authorizationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service().verifyAndReserve("A1", new BigDecimal("60"), NOW);
        assertEquals(new BigDecimal("60"), a.getDailyUsed());
    }

    @Test
    @DisplayName("verify: amount over per-tx limit rejected")
    void overPerTxLimit() {
        when(authorizationRepository.findByAuthorizationId("A1"))
                .thenReturn(Optional.of(auth("PRE_AUTHORIZED", "50", "1000")));
        assertThrows(AgentAuthorizationException.class, () ->
                service().verifyAndReserve("A1", new BigDecimal("60"), NOW));
    }

    @Test
    @DisplayName("verify: daily budget exhausted on the second same-day call")
    void dailyBudgetExhausted() {
        AgentAuthorizationEntity a = auth("PRE_AUTHORIZED", "100", "80");
        when(authorizationRepository.findByAuthorizationId("A1")).thenReturn(Optional.of(a));
        when(authorizationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service().verifyAndReserve("A1", new BigDecimal("60"), NOW);
        assertThrows(AgentAuthorizationException.class, () ->
                service().verifyAndReserve("A1", new BigDecimal("60"), NOW));
    }

    @Test
    @DisplayName("verify: usage resets when a new day starts")
    void dailyResetOnNewDate() {
        AgentAuthorizationEntity a = auth("PRE_AUTHORIZED", "100", "80");
        // burn yesterday's budget to the cap
        a.verifyForPayment(new BigDecimal("60"), YESTERDAY);
        a.reserveDaily(new BigDecimal("60"), YESTERDAY);
        when(authorizationRepository.findByAuthorizationId("A1")).thenReturn(Optional.of(a));
        when(authorizationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // today the budget is fresh again
        service().verifyAndReserve("A1", new BigDecimal("60"), NOW);
        assertEquals(new BigDecimal("60"), a.getDailyUsed());
    }

    @Test
    @DisplayName("verify: revoked authorization rejected")
    void revokedRejected() {
        AgentAuthorizationEntity a = auth("PRE_AUTHORIZED", "100", "1000");
        a.revoke("user kill switch", NOW);
        when(authorizationRepository.findByAuthorizationId("A1")).thenReturn(Optional.of(a));
        assertThrows(AgentAuthorizationException.class, () ->
                service().verifyAndReserve("A1", new BigDecimal("10"), NOW));
    }

    @Test
    @DisplayName("verify: expired authorization rejected")
    void expiredRejected() {
        AgentAuthorizationEntity a = new AgentAuthorizationEntity("A1", "AG1", "U1",
                AgentAuthorizationEntity.DecisionMode.PRE_AUTHORIZED, "{}",
                new BigDecimal("100"), new BigDecimal("1000"),
                YESTERDAY.minusSeconds(172800), YESTERDAY, "v1", YESTERDAY);
        when(authorizationRepository.findByAuthorizationId("A1")).thenReturn(Optional.of(a));
        assertThrows(AgentAuthorizationException.class, () ->
                service().verifyAndReserve("A1", new BigDecimal("10"), NOW));
    }

    @Test
    @DisplayName("revoke: only the principal may revoke")
    void revokeOwnershipEnforced() {
        when(authorizationRepository.findByAuthorizationId("A1"))
                .thenReturn(Optional.of(auth("PRE_AUTHORIZED", "100", "1000")));
        assertThrows(AgentAuthorizationException.class, () ->
                service().revoke("A1", "someone-else", "hijack attempt", NOW));
    }

    @Test
    @DisplayName("revoke is idempotent")
    void revokeIdempotent() {
        when(authorizationRepository.findByAuthorizationId("A1"))
                .thenReturn(Optional.of(auth("PRE_AUTHORIZED", "100", "1000")));
        when(authorizationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service().revoke("A1", "U1", "wallet kill switch", NOW);
        AgentAuthorizationEntity a = service().revoke("A1", "U1", "wallet kill switch", NOW);
        assertEquals(AgentAuthorizationEntity.AuthStatus.REVOKED, a.getStatus());
    }

    @Test
    @DisplayName("intent log stores digests only, never raw prompts")
    void intentDigestOnly() {
        when(intentLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AgentIntentLogEntity log = service().logIntent("A1", "AG1", "U1",
                "buy course 42 for me", "purchased course 42", "P1", "ALLOW",
                "reasoning chain v1", NOW);
        assertEquals(64, log.getInstructionDigest().length());
        assertEquals(64, log.getReasoningDigest().length());
        assertEquals("ALLOW", log.getRiskOutcome());
    }

    @Test
    @DisplayName("decision modes expose confirmation/autonomy flags")
    void modeFlags() {
        assertTrue(auth("CONFIRM_REQUIRED", "100", "1000").requiresConfirmation());
        assertFalse(auth("AUTONOMOUS", "100", "1000").requiresConfirmation());
        assertTrue(auth("AUTONOMOUS", "100", "1000").allowsAutonomous());
        assertTrue(auth("PRE_AUTHORIZED", "100", "1000").allowsAutonomous());
        assertFalse(auth("CONFIRM_REQUIRED", "100", "1000").allowsAutonomous());
    }
}
