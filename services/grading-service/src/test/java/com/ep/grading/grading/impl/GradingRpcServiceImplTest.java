package com.ep.grading.grading.impl;

import com.ep.grading.api.grading.GraderType;
import com.ep.grading.api.grading.GradingRequest;
import com.ep.grading.api.grading.GradingResult;
import com.ep.grading.api.grading.GradingRpcService;
import com.ep.grading.grading.dto.StartGradingCmd;
import com.ep.grading.grading.entity.GradePipeline;
import com.ep.grading.grading.service.GradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-050 P2-5: unit tests for the grading-service grading Dubbo provider.
 *
 * <p>Covers:
 * <ul>
 *   <li>Objective path: blank rubricId → {@code graderType=MANUAL}, deterministic score.</li>
 *   <li>Subjective path: non-blank rubricId → placeholder manual score pending AI integration.</li>
 *   <li>Validation: null request / null submissionId / null tenantId → throws.</li>
 *   <li>Pipeline traceability: {@link GradingService#startGrading} invoked when
 *       the grading service is present; failures are swallowed (result returned).</li>
 *   <li>No-service path: gradingService=null skips pipeline write, still returns result.</li>
 * </ul>
 *
 * <p>No Dubbo registry / Nacos / Sentinel runtime is required — the impl is a
 * plain POJO; annotations are inert outside a Spring Boot context.
 */
class GradingRpcServiceImplTest {

    private GradingService gradingService;
    private GradingRpcService rpc;

    @BeforeEach
    void setUp() {
        gradingService = mock(GradingService.class);
        when(gradingService.startGrading(any(StartGradingCmd.class)))
                .thenReturn(new GradePipeline());
        rpc = new GradingRpcServiceImpl(gradingService);
    }

    @Test
    void gradeObjective_answered_returnsManualScoreAndTracesPipeline() {
        GradingRequest req = new GradingRequest(1L, 100L, 10L, "Paris", null, "t-1");
        GradingResult result = rpc.gradeSubmission(req);

        assertNotNull(result);
        assertEquals("t-1", result.tenantId());
        assertEquals(GraderType.MANUAL, result.graderType());
        assertNotNull(result.score());
        assertNotNull(result.gradedAt());
        // Pipeline traceability: a single startGrading call records the grade.
        verify(gradingService).startGrading(any(StartGradingCmd.class));
    }

    @Test
    void gradeObjective_blankAnswer_scoresZero() {
        GradingRequest req = new GradingRequest(2L, 100L, 11L, "  ", null, "t-1");
        GradingResult result = rpc.gradeSubmission(req);

        assertEquals(0, result.score());
        assertEquals(GraderType.MANUAL, result.graderType());
        verify(gradingService).startGrading(any(StartGradingCmd.class));
    }

    @Test
    void gradeSubjective_withRubric_returnsManualPlaceholder() {
        GradingRequest req = new GradingRequest(3L, 100L, 12L, "essay text", "rubric-7", "t-2");
        GradingResult result = rpc.gradeSubmission(req);

        assertEquals(GraderType.MANUAL, result.graderType());
        assertEquals(75, result.score());
        assertEquals("t-2", result.tenantId());
        // Feedback must mention the rubric id so reviewers can trace it.
        assertNotNull(result.feedback());
        verify(gradingService).startGrading(any(StartGradingCmd.class));
    }

    @Test
    void gradeSubjective_blankAnswer_scoresZero() {
        GradingRequest req = new GradingRequest(4L, 100L, 13L, "", "rubric-7", "t-2");
        GradingResult result = rpc.gradeSubmission(req);

        assertEquals(0, result.score());
        assertEquals(GraderType.MANUAL, result.graderType());
    }

    @Test
    void pipelineFailure_isSwallowed_resultStillReturned() {
        when(gradingService.startGrading(any(StartGradingCmd.class)))
                .thenThrow(new RuntimeException("pipeline down"));
        GradingRequest req = new GradingRequest(5L, 100L, 14L, "answer", null, "t-1");

        GradingResult result = rpc.gradeSubmission(req); // must not propagate

        assertNotNull(result);
        assertEquals("t-1", result.tenantId());
        assertEquals(GraderType.MANUAL, result.graderType());
    }

    @Test
    void noGradingService_skipsPipeline_returnsResult() {
        GradingRpcService bare = new GradingRpcServiceImpl(null);
        GradingRequest req = new GradingRequest(6L, 100L, 15L, "answer", null, "t-1");

        GradingResult result = bare.gradeSubmission(req);

        assertNotNull(result);
        assertEquals("t-1", result.tenantId());
        // gradingService is null → no startGrading call to verify, but no NPE either.
    }

    @Test
    void nullRequest_throws() {
        assertThrows(NullPointerException.class, () -> rpc.gradeSubmission(null));
    }

    @Test
    void nullSubmissionId_throws() {
        GradingRequest req = new GradingRequest(null, 100L, 16L, "a", null, "t-1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> rpc.gradeSubmission(req));
        assertEquals("submissionId must not be null", ex.getMessage());
    }

    @Test
    void nullTenantId_throws() {
        GradingRequest req = new GradingRequest(7L, 100L, 17L, "a", null, null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> rpc.gradeSubmission(req));
        assertEquals("tenantId must not be null", ex.getMessage());
        // Validation must fire BEFORE any pipeline write.
        verify(gradingService, never()).startGrading(any());
    }
}
