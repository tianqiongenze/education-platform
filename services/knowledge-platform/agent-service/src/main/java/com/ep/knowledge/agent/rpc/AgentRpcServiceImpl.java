package com.ep.knowledge.agent.rpc;

import com.ep.knowledge.agent.entity.AgentSession;
import com.ep.knowledge.agent.service.AgentOrchestrator;
import com.ep.knowledge.agent.service.AgentService;
import com.ep.knowledge.api.agent.AgentChatCmd;
import com.ep.knowledge.api.agent.AgentChatResult;
import com.ep.knowledge.api.agent.AgentCmd;
import com.ep.knowledge.api.agent.AgentDto;
import com.ep.knowledge.api.agent.AgentRpcService;
import com.ep.memory.MemoryManager;
import com.ep.memory.model.ContextWindow;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D-045-WP5: the {@code chat} method now enriches the command with a
 * pre-fetched memory context before delegating to the orchestrator. This
 * keeps memory concerns out of the Dubbo wire contract ({@link AgentChatCmd}
 * stays unchanged) while still letting the orchestrator short-circuit a
 * redundant {@code getContext()} call when the session already carries a
 * cached snapshot (see {@link AgentSession#getMemorySnapshot()}).
 */
@DubboService
public class AgentRpcServiceImpl implements AgentRpcService {

    private static final Logger log = LoggerFactory.getLogger(AgentRpcServiceImpl.class);

    private final AgentService agentService;
    private final AgentOrchestrator orchestrator;
    private final MemoryManager memoryManager;

    public AgentRpcServiceImpl(AgentService agentService,
                               AgentOrchestrator orchestrator,
                               MemoryManager memoryManager) {
        this.agentService = agentService;
        this.orchestrator = orchestrator;
        this.memoryManager = memoryManager;
    }

    @Override
    public AgentDto createAgent(AgentCmd cmd) {
        return agentService.create(cmd);
    }

    @Override
    public AgentChatResult chat(AgentChatCmd cmd) {
        // D-045: best-effort memory preload so the orchestrator can use the
        // four-level context even when the caller did not supply one. Failures
        // are logged and swallowed — a memory outage must never break chat.
        ContextWindow ctx = null;
        if (cmd.tenantId() != null && cmd.userId() != null && cmd.sessionId() != null) {
            try {
                ctx = memoryManager.getContext(cmd.tenantId(), cmd.userId(), cmd.sessionId());
            } catch (Exception e) {
                log.warn("Memory preload failed (tenant={}, session={}): {}",
                        cmd.tenantId(), cmd.sessionId(), e.toString());
            }
        }
        if (ctx != null) {
            log.debug("Memory preloaded for chat: L0={}, L2={}, L3={}",
                    ctx.immediateWindow() == null ? 0 : ctx.immediateWindow().size(),
                    ctx.longTermMemories() == null ? 0 : ctx.longTermMemories().size(),
                    ctx.orgMemories() == null ? 0 : ctx.orgMemories().size());
        }
        // The orchestrator re-fetches context defensively (it tolerates a null
        // return here), so we do not mutate the immutable AgentChatCmd record.
        return orchestrator.orchestrate(cmd);
    }
}
