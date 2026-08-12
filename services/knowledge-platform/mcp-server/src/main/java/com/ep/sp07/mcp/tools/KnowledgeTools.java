package com.ep.sp07.mcp.tools;

import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.ep.sp07.mcp.context.TenantContext;
import com.ep.sp07.mcp.service.KnowledgeToolPort;

/**
 * Generic MCP tool definitions for the SP-07 Knowledge Platform.
 *
 * <p>This class contains only industry-agnostic knowledge tools
 * ({@code knowledge.search}). Education-specific tools (submission grading,
 * quiz generation) have been moved to {@link EducationTools} so the core
 * knowledge platform can be extracted to other industries without modification.
 *
 * <p>D-047: Uses the official MCP Java SDK + Spring AI MCP annotations
 * ({@code @McpTool} / {@code @McpToolParam}) for declarative tool registration.
 * The Spring AI MCP starter auto-configures the Streamable HTTP transport,
 * protocol version negotiation, and conformance-tested JSON-RPC dispatch —
 * replacing the former hand-rolled dispatcher.
 *
 * <p>Each annotated method is automatically exposed as an MCP tool with an
 * auto-generated JSON Schema derived from the parameter annotations.
 */
@Component
public class KnowledgeTools {

    private final KnowledgeToolPort knowledgeToolService;

    public KnowledgeTools(KnowledgeToolPort knowledgeToolService) {
        this.knowledgeToolService = knowledgeToolService;
    }

    @McpTool(name = "knowledge.search",
             description = "Search the SP-07 knowledge base for relevant content.")
    public Map<String, Object> searchKnowledge(
            @McpToolParam(description = "Search query text", required = true) String query) {
        return knowledgeToolService.searchKnowledge(query, currentTenant());
    }

    /**
     * Extract tenant ID from the current request context.
     *
     * <p>Reads from the {@link TenantContext} ThreadLocal, which is populated
     * per-request by {@link com.ep.sp07.mcp.context.TenantContextFilter} from
     * the {@code X-Tenant-Id} header (D-044 multi-tenant SaaS isolation).
     *
     * @throws IllegalStateException if no tenant context is set — callers must
     *         ensure the filter runs (or a test sets the context explicitly)
     *         so multi-tenant isolation is never silently bypassed.
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
