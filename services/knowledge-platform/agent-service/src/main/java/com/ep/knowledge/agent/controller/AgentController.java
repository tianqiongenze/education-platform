package com.ep.knowledge.agent.controller;

import com.ep.web.common.RoleChecker;
import com.ep.knowledge.agent.service.AgentOrchestrator;
import com.ep.knowledge.agent.service.AgentService;
import com.ep.knowledge.api.agent.AgentChatCmd;
import com.ep.knowledge.api.agent.AgentChatResult;
import com.ep.knowledge.api.agent.AgentCmd;
import com.ep.knowledge.api.agent.AgentDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents")
@Tag(name = "智能体管理", description = "Agent创建与编排")
public class AgentController {

    private final AgentService agentService;
    private final AgentOrchestrator orchestrator;

    public AgentController(AgentService agentService, AgentOrchestrator orchestrator) {
        this.agentService = agentService;
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public AgentDto create(@RequestHeader("X-Tenant-Id") String tenantId,
                           @RequestBody Map<String, Object> body,
                           HttpServletRequest request) {
        RoleChecker.requireRole(request, "admin", "platform_admin");
        AgentCmd cmd = new AgentCmd(
                tenantId,
                (String) body.get("name"),
                (String) body.get("systemPrompt"),
                (java.util.List<String>) body.getOrDefault("knowledgeBaseIds", java.util.List.of()),
                (java.util.List<String>) body.getOrDefault("tools", java.util.List.of()),
                (String) body.getOrDefault("mode", "ROUTER")
        );
        return agentService.create(cmd);
    }

    @PostMapping("/{id}/chat")
    public AgentChatResult chat(@RequestHeader("X-Tenant-Id") String tenantId,
                                @PathVariable String id,
                                @RequestBody Map<String, Object> body,
                                HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "platform_admin");
        AgentChatCmd cmd = new AgentChatCmd(
                tenantId,
                id,
                (String) body.get("sessionId"),
                (String) body.get("userId"),
                (String) body.get("message"),
                java.util.Map.of()
        );
        return orchestrator.orchestrate(cmd);
    }
}
