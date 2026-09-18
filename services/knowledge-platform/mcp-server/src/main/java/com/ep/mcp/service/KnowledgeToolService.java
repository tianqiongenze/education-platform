package com.ep.mcp.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.ep.grading.api.grading.GradingRequest;
import com.ep.grading.api.grading.GradingResult;
import com.ep.grading.api.grading.GradingRpcService;
import com.ep.knowledge.api.retrieval.QueryCmd;
import com.ep.knowledge.api.retrieval.RetrievalRpcService;
import com.ep.knowledge.api.retrieval.SearchHit;
import com.ep.knowledge.api.retrieval.SearchResult;

import org.apache.dubbo.config.annotation.DubboReference;

/**
 * P2-5 (audit D-048 → D-050): replaces the previous mock-data implementation with
 * real Dubbo RPC calls to retrieval-service and grading-service.
 * <p>
 * Iron rule satisfied: "禁止手写已存在中间件内置能力" - knowledge search now delegates
 * to the actual {@link RetrievalRpcService} Dubbo provider instead of returning
 * hard-coded fake results.
 * <p>
 * The {@code knowledge.search} and {@code quiz.generate} tools delegate to the
 * retrieval service. The {@code submission.grade} tool delegates to the grading
 * {@link GradingRpcService} Dubbo provider (D-050 P2-5 contract closure) — it no
 * longer throws {@link UnsupportedOperationException}; instead it builds a
 * {@link GradingRequest}, invokes the provider, and maps the
 * {@link GradingResult} into the MCP tool response map.
 * <p>
 * Resilience: Dubbo calls degrade gracefully via try/catch so that an MCP tool
 * failure never crashes the server - it returns an error map instead. This is
 * especially important for {@code submission.grade} because the grading provider
 * may be down or rate-limited (Sentinel); the MCP server must keep serving
 * other tools.
 */
@Service
@Profile("!dev-mock")
public class KnowledgeToolService implements KnowledgeToolPort {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeToolService.class);

    /**
     * Dubbo reference to the retrieval service. In the MCP server's test/dev
     * environment this may be absent; production wiring is handled by Dubbo +
     * Nacos service discovery.
     */
    @DubboReference(check = false, timeout = 5000)
    private RetrievalRpcService retrievalRpcService;

    /**
     * D-050 P2-5: Dubbo reference to the grading service. {@code check=false}
     * so unit tests / dev environments without Nacos boot cleanly; production
     * wiring is by Dubbo + Nacos service discovery. Failures degrade to an error
     * map (see {@link #gradeSubmission}).
     */
    @DubboReference(check = false, timeout = 5000)
    private GradingRpcService gradingRpcService;

    /**
     * No-arg constructor used by Spring component scanning + Dubbo field
     * injection. Kept explicit for clarity.
     */
    public KnowledgeToolService() {
    }

    /**
     * Test-friendly constructor: lets unit tests inject mocked Dubbo references
     * without reflection. Either argument may be {@code null} to exercise the
     * degradation path (RPC field is null → NPE caught in the try/catch).
     */
    KnowledgeToolService(RetrievalRpcService retrievalRpcService,
                         GradingRpcService gradingRpcService) {
        this.retrievalRpcService = retrievalRpcService;
        this.gradingRpcService = gradingRpcService;
    }

    /**
     * Search the knowledge base for the supplied query.
     */
    @Override
    public Map<String, Object> searchKnowledge(String query, String tenantId) {
        try {
            QueryCmd cmd = new QueryCmd(tenantId, query, List.of(), 10, Map.of());
            SearchResult result = retrievalRpcService.search(cmd);
            if (result == null) {
                return Map.of("tool", "knowledge.search", "tenantId", tenantId,
                        "query", query, "results", List.of(), "total", 0);
            }
            List<Map<String, Object>> hits = result.hits() == null ? List.of() :
                    result.hits().stream()
                            .map(h -> Map.<String, Object>of(
                                    "id", h.documentId() == null ? "" : h.documentId(),
                                    "title", h.title() == null ? "" : h.title(),
                                    "score", h.score(),
                                    "snippet", h.content() == null ? "" : h.content()))
                            .toList();
            return Map.of(
                    "tool", "knowledge.search",
                    "tenantId", tenantId,
                    "query", query,
                    "results", hits,
                    "total", result.total(),
                    "elapsedMillis", result.elapsedMillis()
            );
        } catch (Exception e) {
            log.warn("knowledge.search RPC failed, returning empty: {}", e.getMessage());
            return Map.of("tool", "knowledge.search", "tenantId", tenantId,
                    "query", query, "results", List.of(), "total", 0,
                    "error", e.getMessage());
        }
    }

    /**
     * Grade a student submission by its id.
     * <p>
     * D-050 P2-5: delegates to the grading {@link GradingRpcService} Dubbo provider
     * (contract published in grading-api). Replaces the previous
     * {@code UnsupportedOperationException} fail-loud stub. On RPC failure the
     * tool returns an error map rather than throwing, so the MCP server keeps
     * serving other tools.
     *
     * @param submissionId logical submission id; parsed to {@code Long} for the
     *                     Dubbo call. Non-numeric ids return an error map.
     * @param tenantId     multi-tenant isolation key (D-044)
     */
    @Override
    public Map<String, Object> gradeSubmission(String submissionId, String tenantId) {
        Long subId;
        try {
            subId = Long.valueOf(submissionId);
        } catch (NumberFormatException e) {
            return Map.of("tool", "submission.grade", "tenantId", tenantId,
                    "submissionId", String.valueOf(submissionId),
                    "status", "error",
                    "error", "submissionId must be numeric: " + submissionId);
        }
        try {
            GradingRequest request = new GradingRequest(subId, null, null, null, null, tenantId);
            GradingResult result = gradingRpcService.gradeSubmission(request);
            if (result == null) {
                return Map.of("tool", "submission.grade", "tenantId", tenantId,
                        "submissionId", String.valueOf(submissionId),
                        "status", "error",
                        "error", "grading provider returned null");
            }
            return Map.of(
                    "tool", "submission.grade",
                    "tenantId", tenantId,
                    "submissionId", String.valueOf(submissionId),
                    "status", "graded",
                    "score", result.score() == null ? 0 : result.score(),
                    "feedback", result.feedback() == null ? "" : result.feedback(),
                    "graderType", result.graderType() == null ? "" : result.graderType().name(),
                    "gradedAt", result.gradedAt() == null ? "" : result.gradedAt().toString()
            );
        } catch (Exception e) {
            log.warn("submission.grade RPC failed for submission {}: {}",
                    submissionId, e.getMessage());
            return Map.of("tool", "submission.grade", "tenantId", tenantId,
                    "submissionId", String.valueOf(submissionId),
                    "status", "error",
                    "error", e.getMessage());
        }
    }

    /**
     * Generate a quiz for the given topic.
     * <p>
     * Delegates to the retrieval service to find relevant content, then
     * synthesises placeholder questions. A real implementation would call an
     * LLM; that wiring is tracked separately.
     */
    @Override
    public Map<String, Object> generateQuiz(String topic, int count, String tenantId) {
        try {
            QueryCmd cmd = new QueryCmd(tenantId, topic, List.of(), count, Map.of());
            SearchResult result = retrievalRpcService.search(cmd);
            List<Map<String, Object>> questions = new java.util.ArrayList<>();
            List<SearchHit> hits = result == null || result.hits() == null ? List.of() : result.hits();
            for (int i = 0; i < count; i++) {
                SearchHit hit = hits.isEmpty() ? null : hits.get(i % hits.size());
                String title = hit == null ? topic : hit.title();
                questions.add(Map.of(
                        "id", "q-" + (i + 1),
                        "question", "Based on \"" + title + "\", question #" + (i + 1) + "?",
                        "options", List.of("A. option", "B. option", "C. option", "D. option"),
                        "answer", "A",
                        "explanation", "Explanation for question " + (i + 1)
                ));
            }
            return Map.of(
                    "tool", "quiz.generate",
                    "tenantId", tenantId,
                    "topic", topic,
                    "count", count,
                    "questions", questions,
                    "generatedAt", java.time.Instant.now().toString()
            );
        } catch (Exception e) {
            log.warn("quiz.generate failed, returning empty: {}", e.getMessage());
            return Map.of("tool", "quiz.generate", "tenantId", tenantId,
                    "topic", topic, "count", count, "questions", List.of(),
                    "error", e.getMessage());
        }
    }
}
