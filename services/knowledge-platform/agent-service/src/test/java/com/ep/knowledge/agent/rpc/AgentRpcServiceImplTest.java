package com.ep.knowledge.agent.rpc;

import com.ep.knowledge.agent.service.AgentOrchestrator;
import com.ep.knowledge.agent.service.AgentService;
import com.ep.knowledge.api.agent.AgentChatCmd;
import com.ep.knowledge.api.agent.AgentChatResult;
import com.ep.knowledge.api.agent.AgentCmd;
import com.ep.knowledge.api.agent.AgentDto;
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
 * Mockito-extension unit test for {@link AgentRpcServiceImpl}.
 *
 * <p>The RPC impl preloads a best-effort memory context (D-045) before
 * delegating to the orchestrator. Memory failures are swallowed so a memory
 * outage never breaks chat. This test covers delegation, memory preload, and
 * graceful degradation.
 */
@ExtendWith(MockitoExtension.class)
class AgentRpcServiceImplTest {

    @Mock
    private AgentService agentService;
    @Mock
    private AgentOrchestrator orchestrator;
    @Mock
    private MemoryManager memoryManager;

    private AgentRpcServiceImpl rpcService;

    @BeforeEach
    void setUp() {
        rpcService = new AgentRpcServiceImpl(agentService, orchestrator, memoryManager);
    }

    @Test
    void createAgent_delegates_to_agent_service() {
        AgentDto dto = new AgentDto("a1", "tenant-1", "Helper", "prompt",
                List.of(), List.of(), "ROUTER");
        AgentCmd cmd = new AgentCmd("tenant-1", "Helper", "prompt", List.of(), List.of(), "ROUTER");
        when(agentService.create(cmd)).thenReturn(dto);

        AgentDto result = rpcService.createAgent(cmd);

        assertSame(dto, result);
        verify(agentService, times(1)).create(cmd);
    }

    @Test
    void chat_delegates_to_orchestrator() {
        AgentChatCmd cmd = new AgentChatCmd("tenant-1", "a1", "sess-1", "u1", "hi", Map.of());
        AgentChatResult expected = new AgentChatResult("sess-1", "reply", List.of(), 5L);
        when(orchestrator.orchestrate(cmd)).thenReturn(expected);

        AgentChatResult result = rpcService.chat(cmd);

        assertSame(expected, result);
        verify(orchestrator, times(1)).orchestrate(cmd);
    }

    @Test
    void chat_preloads_memory_context_when_identifiers_present() {
        AgentChatCmd cmd = new AgentChatCmd("tenant-1", "a1", "sess-1", "u1", "hi", Map.of());
        ContextWindow ctx = new ContextWindow(List.of(), "summary", List.of(), List.of());
        when(memoryManager.getContext("tenant-1", "u1", "sess-1")).thenReturn(ctx);
        when(orchestrator.orchestrate(cmd))
                .thenReturn(new AgentChatResult("sess-1", "reply", List.of(), 1L));

        rpcService.chat(cmd);

        // The preload must fire before orchestration.
        verify(memoryManager, times(1)).getContext("tenant-1", "u1", "sess-1");
        verify(orchestrator, times(1)).orchestrate(cmd);
    }

    @Test
    void chat_skips_memory_preload_when_user_id_missing() {
        // Without userId (or tenantId/sessionId) the preload must not fire — it
        // has nothing to look up.
        AgentChatCmd cmd = new AgentChatCmd("tenant-1", "a1", null, null, "hi", Map.of());
        when(orchestrator.orchestrate(cmd))
                .thenReturn(new AgentChatResult("sess-1", "reply", List.of(), 1L));

        rpcService.chat(cmd);

        verify(memoryManager, never()).getContext(any(), any(), any());
        verify(orchestrator, times(1)).orchestrate(cmd);
    }

    @Test
    void chat_swallows_memory_preload_failure() {
        // A memory outage during preload must not propagate; chat must still
        // return the orchestrator's answer.
        AgentChatCmd cmd = new AgentChatCmd("tenant-1", "a1", "sess-1", "u1", "hi", Map.of());
        when(memoryManager.getContext("tenant-1", "u1", "sess-1"))
                .thenThrow(new RuntimeException("memory down"));
        when(orchestrator.orchestrate(cmd))
                .thenReturn(new AgentChatResult("sess-1", "reply", List.of(), 1L));

        AgentChatResult result = assertDoesNotThrow(() -> rpcService.chat(cmd));

        assertNotNull(result);
        assertEquals("reply", result.answer());
        verify(orchestrator, times(1)).orchestrate(cmd);
    }
}
