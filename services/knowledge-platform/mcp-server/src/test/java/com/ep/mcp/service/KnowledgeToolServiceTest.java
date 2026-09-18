package com.ep.mcp.service;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ep.grading.api.grading.GraderType;
import com.ep.grading.api.grading.GradingRequest;
import com.ep.grading.api.grading.GradingResult;
import com.ep.grading.api.grading.GradingRpcService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-5 / D-050 tests for {@link KnowledgeToolService} (the real Dubbo-backed impl).
 *
 * <p>Three coverage areas:
 * <ul>
 *   <li><b>searchKnowledge / generateQuiz</b>: degrade gracefully when no Dubbo
 *       registry is running (retrievalRpcService is null → NPE caught).</li>
 *   <li><b>gradeSubmission happy path</b>: a mocked {@link GradingRpcService}
 *       returns a {@link GradingResult}; we assert the MCP tool response map
 *       carries score / feedback / graderType / status=graded. This is the
 *       D-050 contract-closure test that replaces the old
 *       {@code UnsupportedOperationException} assertion.</li>
 *   <li><b>gradeSubmission degradation</b>: null provider, provider that throws,
 *       and non-numeric submissionId all return an {@code error} map without
 *       propagating exceptions.</li>
 * </ul>
 *
 * <p>The {@link MockKnowledgeToolService} shape is verified in KnowledgeToolsTest.
 */
class KnowledgeToolServiceTest {

    @Test
    void searchKnowledgeDegradesGracefullyWithoutDubbo() {
        KnowledgeToolService service = new KnowledgeToolService(null, null);
        // RetrievalRpcService is null (no Dubbo registry in unit tests) -> NPE caught
        Map<String, Object> r = assertDoesNotThrow(() -> service.searchKnowledge("calculus", "t-1"));
        assertNotNull(r);
        assertEquals("knowledge.search", r.get("tool"));
        assertEquals(0, r.get("total"));
        assertNotNull(r.get("error"));
    }

    @Test
    void gradeSubmission_withMockedProvider_returnsGradedMap() {
        GradingRpcService gradingRpc = mock(GradingRpcService.class);
        Instant gradedAt = Instant.parse("2026-08-05T12:34:56Z");
        GradingResult stub = new GradingResult(
                87, "Good understanding of core concepts.", gradedAt, GraderType.MANUAL, "t-1");
        when(gradingRpc.gradeSubmission(any(GradingRequest.class))).thenReturn(stub);

        KnowledgeToolService service = new KnowledgeToolService(null, gradingRpc);
        Map<String, Object> r = assertDoesNotThrow(() -> service.gradeSubmission("123", "t-1"));

        assertNotNull(r);
        assertEquals("submission.grade", r.get("tool"));
        assertEquals("graded", r.get("status"));
        assertEquals(87, r.get("score"));
        assertEquals("Good understanding of core concepts.", r.get("feedback"));
        assertEquals("MANUAL", r.get("graderType"));
        assertEquals(gradedAt.toString(), r.get("gradedAt"));
        assertEquals("123", r.get("submissionId"));
        assertEquals("t-1", r.get("tenantId"));
    }

    @Test
    void gradeSubmission_providerThrows_degradesToErrorMap() {
        GradingRpcService gradingRpc = mock(GradingRpcService.class);
        when(gradingRpc.gradeSubmission(any(GradingRequest.class)))
                .thenThrow(new RuntimeException("grading provider down"));

        KnowledgeToolService service = new KnowledgeToolService(null, gradingRpc);
        Map<String, Object> r = assertDoesNotThrow(() -> service.gradeSubmission("9", "t-1"));

        assertNotNull(r);
        assertEquals("submission.grade", r.get("tool"));
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("grading provider down"));
    }

    @Test
    void gradeSubmission_nullProvider_degradesToErrorMap() {
        KnowledgeToolService service = new KnowledgeToolService(null, null);
        Map<String, Object> r = assertDoesNotThrow(() -> service.gradeSubmission("1", "t-1"));

        assertNotNull(r);
        assertEquals("error", r.get("status"));
        assertNotNull(r.get("error"));
    }

    @Test
    void gradeSubmission_nonNumericSubmissionId_returnsErrorMap() {
        GradingRpcService gradingRpc = mock(GradingRpcService.class);
        KnowledgeToolService service = new KnowledgeToolService(null, gradingRpc);

        Map<String, Object> r = assertDoesNotThrow(() -> service.gradeSubmission("abc", "t-1"));

        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("numeric"));
    }

    @Test
    void gradeSubmission_nullResultFromProvider_returnsErrorMap() {
        GradingRpcService gradingRpc = mock(GradingRpcService.class);
        when(gradingRpc.gradeSubmission(any(GradingRequest.class))).thenReturn(null);

        KnowledgeToolService service = new KnowledgeToolService(null, gradingRpc);
        Map<String, Object> r = assertDoesNotThrow(() -> service.gradeSubmission("5", "t-1"));

        assertEquals("error", r.get("status"));
        assertNotNull(r.get("error"));
    }

    @Test
    void generateQuizDegradesGracefullyWithoutDubbo() {
        KnowledgeToolService service = new KnowledgeToolService(null, null);
        Map<String, Object> r = assertDoesNotThrow(() -> service.generateQuiz("algebra", 3, "t-1"));
        assertNotNull(r);
        assertEquals("quiz.generate", r.get("tool"));
        assertTrue(((java.util.List<?>) r.get("questions")).isEmpty());
        assertNotNull(r.get("error"));
    }
}
