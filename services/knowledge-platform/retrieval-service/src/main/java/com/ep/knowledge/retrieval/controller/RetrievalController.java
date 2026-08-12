package com.ep.knowledge.retrieval.controller;

import com.ep.knowledge.api.retrieval.QueryCmd;
import com.ep.knowledge.api.retrieval.RagCmd;
import com.ep.knowledge.api.retrieval.RagResult;
import com.ep.knowledge.api.retrieval.SearchResult;
import com.ep.web.common.RoleChecker;
import com.ep.knowledge.retrieval.service.HybridSearchService;
import com.ep.knowledge.retrieval.service.RagService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/retrieval")
@Tag(name = "知识检索", description = "混合检索与RAG问答")
public class RetrievalController {

    private final HybridSearchService hybridSearchService;
    private final RagService ragService;

    public RetrievalController(HybridSearchService hybridSearchService, RagService ragService) {
        this.hybridSearchService = hybridSearchService;
        this.ragService = ragService;
    }

    @PostMapping("/search")
    public SearchResult search(@RequestHeader("X-Tenant-Id") String tenantId,
                               @RequestBody Map<String, Object> body,
                               HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "platform_admin");
        QueryCmd cmd = new QueryCmd(
                tenantId,
                (String) body.get("query"),
                (List<String>) body.getOrDefault("knowledgeBaseIds", List.of()),
                (int) body.getOrDefault("topK", 10),
                Map.of()
        );
        return hybridSearchService.search(cmd);
    }

    @PostMapping("/rag")
    public RagResult rag(@RequestHeader("X-Tenant-Id") String tenantId,
                         @RequestBody Map<String, Object> body,
                         HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "platform_admin");
        RagCmd cmd = new RagCmd(
                tenantId,
                (String) body.get("query"),
                (List<String>) body.getOrDefault("knowledgeBaseIds", List.of()),
                (String) body.get("agentId"),
                (int) body.getOrDefault("topK", 5),
                (boolean) body.getOrDefault("enableRerank", true)
        );
        return ragService.rag(cmd);
    }
}
