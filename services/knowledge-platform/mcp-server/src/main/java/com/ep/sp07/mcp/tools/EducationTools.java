package com.ep.sp07.mcp.tools;

import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.ep.sp07.mcp.context.TenantContext;
import com.ep.sp07.mcp.service.KnowledgeToolPort;

/**
 * Education-industry-specific MCP tools for the SP-07 Knowledge Platform.
 *
 * <p>D-098: These tools ({@code submission.grade}, {@code quiz.generate}) are
 * education-specific and have been separated from the generic {@link KnowledgeTools}
 * so the core knowledge platform can be extracted to other industries without
 * carrying education domain logic. When migrating to a new industry, either:
 * <ul>
 *   <li>Replace this class with an industry-specific tools class (e.g.
 *       {@code HealthcareTools}, {@code RetailTools}), or</li>
 *   <li>Deploy this class alongside the generic platform only when the
 *       education domain is needed.</li>
 * </ul>
 *
 * <p>D-047: Uses the official MCP Java SDK + Spring AI MCP annotations.
 */
@Component
public class EducationTools {

    private final KnowledgeToolPort knowledgeToolService;

    public EducationTools(KnowledgeToolPort knowledgeToolService) {
        this.knowledgeToolService = knowledgeToolService;
    }

    @McpTool(name = "submission.grade",
             description = "Grade a student submission by its ID and return score with feedback.")
    public Map<String, Object> gradeSubmission(
            @McpToolParam(description = "Submission ID to grade", required = true) String submissionId) {
        return knowledgeToolService.gradeSubmission(submissionId, currentTenant());
    }

    @McpTool(name = "quiz.generate",
             description = "Generate a quiz for a given topic with a specified number of questions.")
    public Map<String, Object> generateQuiz(
            @McpToolParam(description = "Quiz topic", required = true) String topic,
            @McpToolParam(description = "Number of questions (default 3)") Integer count) {
        int qCount = count == null ? 3 : count;
        return knowledgeToolService.generateQuiz(topic, qCount, currentTenant());
    }

    /**
     * Extract tenant ID from the current request context.
     *
     * <p>Reads from the {@link TenantContext} ThreadLocal, which is populated
     * per-request by {@link com.ep.sp07.mcp.context.TenantContextFilter} from
     * the {@code X-Tenant-Id} header (D-044 multi-tenant SaaS isolation).
     *
     * @throws IllegalStateException if no tenant context is set.
     */
    String currentTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException(
                    "Tenant context not set; X-Tenant-Id header missing or TenantContextFilter not configured");
        }
        return tenantId;
    }
}
