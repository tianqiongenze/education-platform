package com.ep.mcp.tools;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ep.mcp.context.TenantContext;
import com.ep.mcp.service.KnowledgeToolPort;
import com.ep.mcp.service.MockKnowledgeToolService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link EducationTools} — verifies the education-specific
 * tools ({@code submission.grade}, {@code quiz.generate}) delegate correctly
 * to the {@link KnowledgeToolPort}.
 *
 * <p>Uses the dev-only {@link MockKnowledgeToolService}.
 */
class EducationToolsTest {

    private final KnowledgeToolPort service = new MockKnowledgeToolService();
    private final EducationTools tools = new EducationTools(service);

    @BeforeEach
    void setTenant() {
        TenantContext.setTenantId("t-edu");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void gradeSubmission_returnsScore() {
        Map<String, Object> result = tools.gradeSubmission("sub-001");
        assertNotNull(result);
        assertEquals("graded", result.get("status"));
        assertNotNull(result.get("score"));
        assertNotNull(result.get("feedback"));
    }

    @Test
    void generateQuiz_defaultCount() {
        Map<String, Object> result = tools.generateQuiz("algebra", null);
        assertNotNull(result);
        assertEquals(3, ((java.util.List<?>) result.get("questions")).size());
    }

    @Test
    void generateQuiz_customCount() {
        Map<String, Object> result = tools.generateQuiz("geometry", 5);
        assertNotNull(result);
        assertEquals(5, ((java.util.List<?>) result.get("questions")).size());
    }

    @Test
    void currentTenant_throwsWhenNotSet() {
        TenantContext.clear();
        assertThrows(IllegalStateException.class, tools::currentTenant);
    }

    @Test
    void currentTenant_returnsSetTenant() {
        assertEquals("t-edu", tools.currentTenant());
    }
}
