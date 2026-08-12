package com.ep.knowledge.agent.service.impl;

import com.ep.knowledge.agent.entity.Agent;
import com.ep.knowledge.agent.service.AgentOrchestrator;
import com.ep.knowledge.agent.service.AgentService;
import com.ep.knowledge.api.agent.AgentChatCmd;
import com.ep.knowledge.api.agent.AgentChatResult;
import com.ep.memory.MemoryManager;
import com.ep.memory.model.ContextWindow;
import com.ep.memory.model.MemoryAddRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestration engine with three modes: ROUTER (single best path),
 * PIPELINE (sequential steps), DEBATE (multi-agent consensus).
 *
 * <p>D-045: integrates the multi-level memory system. Before orchestration
 * the four-level {@link ContextWindow} is loaded and injected into the
 * orchestration context; after orchestration the turn is persisted
 * asynchronously via a virtual thread (D-039).</p>
 */
@Service
public class AgentOrchestratorImpl implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorImpl.class);

    private final AgentService agentService;
    private final MemoryManager memoryManager;

    public AgentOrchestratorImpl(AgentService agentService, MemoryManager memoryManager) {
        this.agentService = agentService;
        this.memoryManager = memoryManager;
    }

    @Override
    public AgentChatResult orchestrate(AgentChatCmd cmd) {
        long start = System.currentTimeMillis();
        Agent agent = agentService.get(cmd.tenantId(), cmd.agentId());
        String mode = agent == null ? "ROUTER" : agent.getMode();
        List<Map<String, Object>> steps = new ArrayList<>();

        // ---- D-045: load memory context before orchestration ----
        ContextWindow ctx = loadContext(cmd);
        Map<String, Object> memoryContext = buildMemoryContext(ctx);
        if (ctx != null) {
            log.debug("Memory injected: L0={}, L1.summary.present={}, L2={}, L3={}",
                    ctx.immediateWindow() == null ? 0 : ctx.immediateWindow().size(),
                    ctx.workingSummary() != null && !ctx.workingSummary().isBlank(),
                    ctx.longTermMemories() == null ? 0 : ctx.longTermMemories().size(),
                    ctx.orgMemories() == null ? 0 : ctx.orgMemories().size());
        }

        switch (mode == null ? "ROUTER" : mode) {
            case "PIPELINE" -> steps.addAll(runPipeline(cmd, agent, memoryContext));
            case "DEBATE" -> steps.addAll(runDebate(cmd, agent, memoryContext));
            default -> steps.addAll(runRouter(cmd, agent, memoryContext));
        }

        String answer = "Agent[" + (agent == null ? "unknown" : agent.getName()) + "] reply: " + cmd.message();
        long elapsed = System.currentTimeMillis() - start;
        String sessionId = cmd.sessionId() != null ? cmd.sessionId() : UUID.randomUUID().toString();

        // ---- D-045: persist this turn asynchronously (virtual thread, D-039) ----
        persistTurn(cmd, sessionId, answer);

        return new AgentChatResult(sessionId, answer, steps, elapsed);
    }

    private ContextWindow loadContext(AgentChatCmd cmd) {
        try {
            return memoryManager.getContext(cmd.tenantId(), cmd.userId(), cmd.sessionId());
        } catch (Exception e) {
            log.warn("Memory context load failed (tenant={}): {}", cmd.tenantId(), e.toString());
            return null;
        }
    }

    private Map<String, Object> buildMemoryContext(ContextWindow ctx) {
        if (ctx == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("immediateWindow", ctx.immediateWindow());
        map.put("workingSummary", ctx.workingSummary());
        map.put("longTermMemories", ctx.longTermMemories());
        map.put("orgMemories", ctx.orgMemories());
        return map;
    }

    private void persistTurn(AgentChatCmd cmd, String sessionId, String answer) {
        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", cmd.message() == null ? "" : cmd.message()),
                Map.of("role", "assistant", "content", answer));
        MemoryAddRequest addReq = new MemoryAddRequest(
                cmd.tenantId(), cmd.userId(), sessionId, messages);
        // D-039: fire-and-forget on a virtual thread; memory failure must not break the turn.
        Thread.startVirtualThread(() -> {
            try {
                memoryManager.addMemory(addReq);
            } catch (Exception e) {
                log.warn("Memory persist failed (tenant={}): {}", cmd.tenantId(), e.toString());
            }
        });
    }

    private List<Map<String, Object>> runRouter(AgentChatCmd cmd, Agent agent, Map<String, Object> memoryContext) {
        log.debug("Router mode for agent={}", cmd.agentId());
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", "route");
        step.put("target", "retrieval");
        step.put("memory", memoryContext);
        return List.of(step);
    }

    private List<Map<String, Object>> runPipeline(AgentChatCmd cmd, Agent agent, Map<String, Object> memoryContext) {
        log.debug("Pipeline mode for agent={}", cmd.agentId());
        Map<String, Object> retrieve = new LinkedHashMap<>();
        retrieve.put("step", "retrieve");
        retrieve.put("memory", memoryContext);
        return List.of(
                retrieve,
                Map.of("step", "reason"),
                Map.of("step", "generate")
        );
    }

    private List<Map<String, Object>> runDebate(AgentChatCmd cmd, Agent agent, Map<String, Object> memoryContext) {
        log.debug("Debate mode for agent={}", cmd.agentId());
        Map<String, Object> proposition = new LinkedHashMap<>();
        proposition.put("step", "proposition");
        proposition.put("memory", memoryContext);
        return List.of(
                proposition,
                Map.of("step", "opposition"),
                Map.of("step", "synthesis")
        );
    }
}
