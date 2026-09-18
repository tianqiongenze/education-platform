package com.ep.grading.grading.impl;

import com.ep.grading.api.grading.AiGradingRequest;
import com.ep.grading.api.grading.AiGradingResult;
import com.ep.grading.api.grading.AiGradingRpcService;
import com.ep.grading.api.grading.GraderType;
import com.ep.grading.api.grading.GradingRequest;
import com.ep.grading.api.grading.GradingResult;
import com.ep.grading.api.grading.GradingRpcService;
import com.ep.grading.grading.dto.StartGradingCmd;
import com.ep.grading.grading.entity.GradePipeline;
import com.ep.grading.grading.service.GradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ai-platform integration tests for {@link GradingRpcServiceImpl}'s AI grading
 * wiring (closes the TODO(ai-grading) gap).
 *
 * <p>Covers:
 * <ul>
 *   <li>AI disabled (default): subjective grading returns a MANUAL pending
 *       result without ever calling {@link AiGradingRpcService}.</li>
 *   <li>AI enabled + success: the AI service is called and the AI score /
 *       feedback / {@code graderType=AI} propagate into the result.</li>
 *   <li>AI enabled + exception with {@code fallback=pending}: a graceful
 *       MANUAL pending result is returned (no crash).</li>
 *   <li>AI enabled + exception with {@code fallback=fail}: the exception is
 *       rethrown (fail-loud prod profile).</li>
 *   <li>Blank answer never burns an AI call, regardless of the enabled flag.</li>
 * </ul>
 *
 * <p>Pure Mockito — no Spring Boot context, no Dubbo registry, no real AI.
 * Uses the explicit 4-arg constructor so the type stays a plain POJO.
 */
class AiGradingIntegrationTest {

    private GradingService gradingService;
    private AiGradingRpcService aiGrading;

    @BeforeEach
    void setUp() {
        gradingService = mock(GradingService.class);
        when(gradingService.startGrading(any(StartGradingCmd.class)))
                .thenReturn(new GradePipeline());
        aiGrading = mock(AiGradingRpcService.class);
    }

    private GradingRequest subjective(String answer) {
        return new GradingRequest(101L, 200L, 30L, answer, "rubric-7", "t-ai");
    }

    @Test
    void aiDisabled_returnsManualPendingWithoutCallingAi() {
        GradingRpcService rpc = new GradingRpcServiceImpl(
                gradingService, aiGrading, false, "pending");

        GradingResult result = rpc.gradeSubmission(subjective("essay text"));

        assertNotNull(result);
        assertEquals(GraderType.MANUAL, result.graderType());
        assertEquals("t-ai", result.tenantId());
        verify(aiGrading, never()).grade(any());
    }

    @Test
    void aiEnabled_callsAiAndUsesResult() {
        when(aiGrading.grade(any(AiGradingRequest.class)))
                .thenReturn(new AiGradingResult(
                        88.0, "Good analysis", 0.92, LocalDateTime.now()));
        GradingRpcService rpc = new GradingRpcServiceImpl(
                gradingService, aiGrading, true, "pending");

        GradingResult result = rpc.gradeSubmission(subjective("essay text"));

        assertNotNull(result);
        assertEquals(GraderType.AI, result.graderType());
        assertEquals(88, result.score());
        assertEquals("Good analysis", result.feedback());
        assertEquals("t-ai", result.tenantId());
        assertNotNull(result.gradedAt());
        // The rubric id must propagate to the AI request.
        verify(aiGrading).grade(argThat((AiGradingRequest r) ->
                "rubric-7".equals(r.rubric()) && "t-ai".equals(r.tenantId())));
    }

    @Test
    void aiEnabled_throws_fallbackPending_returnsManualPending() {
        when(aiGrading.grade(any(AiGradingRequest.class)))
                .thenThrow(new RuntimeException("ai-grading provider down"));
        GradingRpcService rpc = new GradingRpcServiceImpl(
                gradingService, aiGrading, true, "pending");

        GradingResult result = rpc.gradeSubmission(subjective("essay text"));

        assertNotNull(result);
        assertEquals(GraderType.MANUAL, result.graderType());
        assertEquals("t-ai", result.tenantId());
        verify(aiGrading).grade(any());
    }

    @Test
    void aiEnabled_throws_fallbackFail_rethrows() {
        when(aiGrading.grade(any(AiGradingRequest.class)))
                .thenThrow(new RuntimeException("ai-grading provider down"));
        GradingRpcService rpc = new GradingRpcServiceImpl(
                gradingService, aiGrading, true, "fail");

        assertThrows(RuntimeException.class,
                () -> rpc.gradeSubmission(subjective("essay text")));
        verify(aiGrading).grade(any());
    }

    @Test
    void aiEnabled_blankAnswer_skipsAiCallAndScoresZero() {
        GradingRpcService rpc = new GradingRpcServiceImpl(
                gradingService, aiGrading, true, "pending");

        GradingResult result = rpc.gradeSubmission(subjective("  "));

        assertNotNull(result);
        assertEquals(0, result.score());
        assertEquals(GraderType.MANUAL, result.graderType());
        verify(aiGrading, never()).grade(any());
    }

    @Test
    void aiEnabled_returnsNull_fallsBackToPending() {
        when(aiGrading.grade(any(AiGradingRequest.class))).thenReturn(null);
        GradingRpcService rpc = new GradingRpcServiceImpl(
                gradingService, aiGrading, true, "pending");

        GradingResult result = rpc.gradeSubmission(subjective("essay text"));

        assertNotNull(result);
        assertEquals(GraderType.MANUAL, result.graderType());
        assertEquals("t-ai", result.tenantId());
    }
}
