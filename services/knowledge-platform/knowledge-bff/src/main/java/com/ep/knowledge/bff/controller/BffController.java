package com.ep.knowledge.bff.controller;

import com.ep.knowledge.api.agent.AgentChatCmd;
import com.ep.knowledge.api.agent.AgentChatResult;
import com.ep.knowledge.api.agent.AgentRpcService;
import com.ep.knowledge.api.retrieval.QueryCmd;
import com.ep.knowledge.api.retrieval.RagCmd;
import com.ep.knowledge.api.retrieval.RagResult;
import com.ep.knowledge.api.retrieval.RetrievalRpcService;
import com.ep.knowledge.api.retrieval.SearchResult;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BFF aggregation controller. Calls retrieval-service and agent-service via Dubbo.
 */
@RestController
@RequestMapping("/api/v1/bff")
public class BffController {

    @DubboReference
    private RetrievalRpcService retrievalRpcService;

    @DubboReference
    private AgentRpcService agentRpcService;

    /**
     * Knowledge overview: top documents + sample search.
     */
    @GetMapping("/knowledge/overview")
    public Map<String, Object> knowledgeOverview(@RequestHeader("X-Tenant-Id") String tenantId) {
        Map<String, Object> overview = new HashMap<>();
        overview.put("tenantId", tenantId);

        QueryCmd cmd = new QueryCmd(tenantId, "", List.of(), 5, Map.of());
        try {
            SearchResult search = retrievalRpcService.search(cmd);
            overview.put("topDocuments", search.hits());
            overview.put("total", search.total());
        } catch (Exception e) {
            overview.put("topDocuments", List.of());
            overview.put("error", e.getMessage());
        }
        return overview;
    }

    /**
     * Agent dashboard: run a sample chat against an agent.
     */
    @PostMapping("/agent/dashboard")
    public Map<String, Object> agentDashboard(@RequestHeader("X-Tenant-Id") String tenantId,
                                               @RequestBody Map<String, String> body) {
        Map<String, Object> dashboard = new HashMap<>();
        String agentId = body.get("agentId");
        String message = body.getOrDefault("message", "Hello");

        AgentChatCmd cmd = new AgentChatCmd(tenantId, agentId, null, "dashboard-user", message, Map.of());
        try {
            AgentChatResult result = agentRpcService.chat(cmd);
            dashboard.put("answer", result.answer());
            dashboard.put("sessionId", result.sessionId());
            dashboard.put("elapsedMillis", result.elapsedMillis());
        } catch (Exception e) {
            dashboard.put("error", e.getMessage());
        }
        return dashboard;
    }

    /**
     * RAG query passthrough.
     */
    @PostMapping("/knowledge/rag")
    public RagResult rag(@RequestHeader("X-Tenant-Id") String tenantId,
                         @RequestBody Map<String, Object> body) {
        RagCmd cmd = new RagCmd(
                tenantId,
                (String) body.get("query"),
                (List<String>) body.getOrDefault("knowledgeBaseIds", List.of()),
                (String) body.get("agentId"),
                (int) body.getOrDefault("topK", 5),
                (boolean) body.getOrDefault("enableRerank", true)
        );
        return retrievalRpcService.ragChat(cmd);
    }
}
