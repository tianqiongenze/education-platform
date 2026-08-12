package com.ep.knowledge.agent.service.impl;

import com.ep.knowledge.agent.entity.Agent;
import com.ep.knowledge.agent.service.AgentOrchestrator;
import com.ep.knowledge.agent.service.AgentService;
import com.ep.knowledge.api.agent.AgentChatCmd;
import com.ep.knowledge.api.agent.AgentChatResult;
import com.ep.memory.MemoryManager;
import com.ep.memory.model.ContextWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Mockito-extension unit test for {@link AgentOrchestratorImpl}.
 *
 * <p>Mocks {@link AgentService} and {@link MemoryManager} so the orchestration
 * engine (ROUTER / PIPELINE / DEBATE modes) can be exercised without Redis or
 * the memory backend. Memory persistence runs on a virtual thread (D-039) and
 * is best-effort, so failures there must not break the turn.
 */
@ExtendWith(MockitoExtension.class)
class AgentOrchestratorImplTest {

    @Mock
    private AgentService agentService;
    @Mock
    private MemoryManager memoryManager;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new AgentOrchestratorImpl(agentService, memoryManager);
    }

    private AgentChatCmd cmd() {
        return new AgentChatCmd("tenant-1", "a1", "sess-1", "u1", "hello", Map.of());
    }

    private Agent agentWithMode(String mode) {
        Agent a = new Agent();
        a.setId("a1");
        a.setTenantId("tenant-1");
        a.setName("Helper");
        a.setMode(mode);
        return a;
    }

    @Test
    void orchestrate_router_mode_emits_single_route_step() {
        when(agentService.get("tenant-1", "a1")).thenReturn(agentWithMode("ROUTER"));

        AgentChatResult result = orchestrator.orchestrate(cmd());

        assertNotNull(result);
        assertEquals("sess-1", result.sessionId());
        assertTrue(result.answer().contains("Helper"), "answer should include agent name");
        assertTrue(result.answer().contains("hello"));
        assertEquals(1, result.steps().size());
        assertEquals("route", result.steps().get(0).get("step"));
        assertTrue(result.elapsedMillis() >= 0);
    }

    @Test
    void orchestrate_pipeline_mode_emits_retrieve_reason_generate() {
        when(agentService.get("tenant-1", "a1")).thenReturn(agentWithMode("PIPELINE"));

        AgentChatResult result = orchestrator.orchestrate(cmd());

        assertEquals(3, result.steps().size());
        assertEquals("retrieve", result.steps().get(0).get("step"));
        assertEquals("reason", result.steps().get(1).get("step"));
        assertEquals("generate", result.steps().get(2).get("step"));
    }

    @Test
    void orchestrate_debate_mode_emits_proposition_opposition_synthesis() {
        when(agentService.get("tenant-1", "a1")).thenReturn(agentWithMode("DEBATE"));

        AgentChatResult result = orchestrator.orchestrate(cmd());

        assertEquals(3, result.steps().size());
        assertEquals("proposition", result.steps().get(0).get("step"));
        assertEquals("opposition", result.steps().get(1).get("step"));
        assertEquals("synthesis", result.steps().get(2).get("step"));
    }

    @Test
    void orchestrate_defaults_to_router_when_agent_missing() {
        // A null agent (wrong id) must not NPE — the mode falls back to ROUTER.
        when(agentService.get("tenant-1", "missing")).thenReturn(null);

        AgentChatCmd c = new AgentChatCmd("tenant-1", "missing", "sess-1", "u1", "hi", Map.of());
        AgentChatResult result = orchestrator.orchestrate(c);

        assertEquals(1, result.steps().size());
        assertEquals("route", result.steps().get(0).get("step"));
        assertTrue(result.answer().contains("unknown"), "answer should mark the agent as unknown");
    }

    @Test
    void orchestrate_generates_session_id_when_cmd_has_none() {
        when(agentService.get("tenant-1", "a1")).thenReturn(agentWithMode("ROUTER"));

        AgentChatCmd c = new AgentChatCmd("tenant-1", "a1", null, "u1", "hi", Map.of());
        AgentChatResult result = orchestrator.orchestrate(c);

        assertNotNull(result.sessionId(), "a new session id must be generated when absent");
        assertDoesNotThrow(() -> java.util.UUID.fromString(result.sessionId()));
    }

    @Test
    void orchestrate_injects_memory_context_into_steps() {
        Agent agent = agentWithMode("ROUTER");
        when(agentService.get("tenant-1", "a1")).thenReturn(agent);
        ContextWindow ctx = new ContextWindow(
                List.of(Map.of("role", "user", "content", "prev")),
                "summary",
                List.of(),
                List.of());
        when(memoryManager.getContext("tenant-1", "u1", "sess-1")).thenReturn(ctx);

        AgentChatResult result = orchestrator.orchestrate(cmd());

        Object memory = result.steps().get(0).get("memory");
        assertNotNull(memory, "memory context must be injected into the step");
        assertInstanceOf(Map.class, memory);
        @SuppressWarnings("unchecked")
        Map<String, Object> memoryMap = (Map<String, Object>) memory;
        assertEquals("summary", memoryMap.get("workingSummary"));
    }

    @Test
    void orchestrate_persists_turn_asynchronously() throws Exception {
        when(agentService.get("tenant-1", "a1")).thenReturn(agentWithMode("ROUTER"));

        orchestrator.orchestrate(cmd());

        // Memory persistence runs on a virtual thread (fire-and-forget). Give it
        // a moment to execute, then assert addMemory was invoked.
        Thread.sleep(100);
        verify(memoryManager, timeout(500)).addMemory(any());
    }

    @Test
    void orchestrate_tolerates_memory_context_load_failure() {
        when(agentService.get("tenant-1", "a1")).thenReturn(agentWithMode("ROUTER"));
        when(memoryManager.getContext(any(), any(), any())).thenThrow(new RuntimeException("down"));

        // A memory outage must not break the turn.
        AgentChatResult result = assertDoesNotThrow(() -> orchestrator.orchestrate(cmd()));

        assertNotNull(result);
        assertEquals(1, result.steps().size());
    }
}
