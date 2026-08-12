package com.ep.sp07.mcp.tools;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ep.sp07.mcp.context.TenantContext;
import com.ep.sp07.mcp.service.KnowledgeToolPort;
import com.ep.sp07.mcp.service.MockKnowledgeToolService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link KnowledgeTools} — verifies the generic
 * {@code knowledge.search} tool delegates correctly to the
 * {@link KnowledgeToolPort}.
 *
 * <p>Education-specific tools ({@code submission.grade}, {@code quiz.generate})
 * have moved to {@link EducationToolsTest}.
 *
 * <p>Uses the dev-only {@link MockKnowledgeToolService} so tests don't need a
 * running Dubbo/Nacos/retrieval stack.
 */
class KnowledgeToolsTest {

    private final KnowledgeToolPort service = new MockKnowledgeToolService();
    private final KnowledgeTools tools = new KnowledgeTools(service);

    @BeforeEach
    void setTenant() {
        TenantContext.setTenantId("t-test");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void searchKnowledge_returnsResults() {
        Map<String, Object> result = tools.searchKnowledge("calculus");
        assertNotNull(result);
        assertEquals("knowledge.search", result.get("tool"));
        assertEquals("calculus", result.get("query"));
        assertTrue((int) result.get("total") > 0);
    }
}
