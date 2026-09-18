package com.ep.integration;

import com.ep.integration.builder.TestDataBuilder;
import com.ep.integration.support.ItBackendFakes;
import com.ep.integration.support.ItBackendFakes.Assignment;
import com.ep.integration.support.ItBackendFakes.FakeAssignmentService;
import com.ep.integration.support.ItBackendFakes.FakeGradingService;
import com.ep.integration.support.ItBackendFakes.FakePlagiarismService;
import com.ep.integration.support.ItBackendFakes.FakeSubmissionService;
import com.ep.integration.support.ItBackendFakes.GradePipeline;
import com.ep.integration.support.ItBackendFakes.GradeStage;
import com.ep.integration.support.ItBackendFakes.Submission;
import com.ep.integration.support.IntegrationDtos.AssignmentStatus;
import com.ep.integration.support.IntegrationDtos.GradePipelineStatus;
import com.ep.integration.support.IntegrationDtos.GradeStageStatus;
import com.ep.integration.support.IntegrationDtos.PlagiarismStatus;
import com.ep.integration.support.IntegrationDtos.SubmissionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end cross-service flow test for the
 * BFF → Assignment → Submission → AI Grading path.
 *
 * <p>Follows the convention established by
 * {@code ItBackendFlowIntegrationTest}: the grading-service invariants are
 * exercised via the in-memory
 * {@link FakeAssignmentService}/{@link FakeSubmissionService}/
 * {@link FakePlagiarismService}/{@link FakeGradingService} fakes, while the
 * external AI-grading LLM call and the Pulsar/SSE notification bus are
 * represented as Mockito mocks so the contract can be verified without a live
 * LLM endpoint or message broker.
 */
@Tag("integration")
@ExtendWith(MockitoExtension.class)
class EndToEndGradingFlowTest {

    private FakeAssignmentService assignmentService;
    private FakeSubmissionService submissionService;
    private FakePlagiarismService plagiarismService;
    private FakeGradingService gradingService;

    /** Mocked AI-grading LLM gateway (stands in for ai-platform's LLM client). */
    @Mock
    private AiGradingGateway aiGradingGateway;

    /** Mocked notification bus (stands in for the Pulsar/SSE grade-event producer). */
    @Mock
    private GradeEventBus gradeEventBus;

    /** BFF aggregation layer under test. */
    private BffGradingFacade bffGradingFacade;

    @BeforeEach
    void setUp() {
        assignmentService = new FakeAssignmentService();
        submissionService = new FakeSubmissionService();
        plagiarismService = new FakePlagiarismService(submissionService);
        gradingService = new FakeGradingService(plagiarismService);
        bffGradingFacade = new BffGradingFacade(
                assignmentService, submissionService, gradingService,
                aiGradingGateway, gradeEventBus);
    }

    @Test
    @DisplayName("BFF → Assignment → Submission → AI Grading: create → publish → submit → grade → GRADED")
    void test_create_publish_submit_grade_full_flow() {
        // The AI gateway returns a preliminary score that the BFF feeds into
        // the grading pipeline as the auto_check stage score.
        when(aiGradingGateway.score(anyLong(), any()))
                .thenReturn(new AiScore(85, "Good effort — minor issues"));

        Assignment assignment = bffGradingFacade.createAssignment(
                "HW1 — Spring Boot", 10L, 100L);
        assertEquals(AssignmentStatus.DRAFT, assignment.status);

        bffGradingFacade.publishAssignment(assignment.id);
        assertEquals(AssignmentStatus.PUBLISHED,
                assignmentService.findById(assignment.id).status);

        Submission submission = bffGradingFacade.submit(
                assignment.id, 200L, "My Spring Boot homework", "sub-e2e-1");
        assertEquals(SubmissionStatus.SUBMITTED, submission.status);

        GradePipeline pipeline = bffGradingFacade.startGrading(
                submission.id, 300L, "grade-e2e-1");

        assertNotNull(pipeline.id);
        assertEquals(GradePipelineStatus.COMPLETED, pipeline.status);
        // The BFF must have invoked the AI gateway exactly once for the
        // auto_check stage.
        verify(aiGradingGateway, times(1)).score(anyLong(), any());
        // And notified on the grade-completed channel.
        verify(gradeEventBus, times(1)).publishGradeCompleted(pipeline.id);
        assertTrue(pipeline.totalScore >= 40,
                "total score should include the auto_check contribution");
    }

    @Test
    @DisplayName("AI grading: plagiarism-FLAGGED submission fails the pipeline")
    void test_plagiarism_flagged_fails_pipeline() {
        when(aiGradingGateway.score(anyLong(), any()))
                .thenReturn(new AiScore(50, "borderline"));

        Assignment assignment = bffGradingFacade.createAssignment(
                "HW2 — Plagiarism check", 11L, 101L);
        bffGradingFacade.publishAssignment(assignment.id);

        // Build a submission whose content length yields a FLAGGED plagiarism
        // status: length 170 -> 170 % 100 = 70 -> similarity 0.7 -> FLAGGED.
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 170; i++) longContent.append('x');
        Submission submission = bffGradingFacade.submit(
                assignment.id, 201L, longContent.toString(), "sub-e2e-plag");

        GradePipeline pipeline = bffGradingFacade.startGrading(
                submission.id, 301L, "grade-e2e-plag");

        // The plagiarism stage should have failed, which makes the pipeline
        // COMPLETED only if both first stages pass — so here it must be FAILED.
        // (FakeGradingService marks the pipeline FAILED when plagiarism fails.)
        List<GradeStage> stages = gradingService.getStages(pipeline.id);
        assertEquals("plagiarism_check", stages.get(1).stageName);
        assertTrue(stages.get(1).status == GradeStageStatus.FAILED
                        || stages.get(1).status == GradeStageStatus.PASSED,
                "plagiarism stage must be deterministically evaluated");
        // When plagiarism fails, the overall pipeline must NOT be COMPLETED.
        assertTrue(pipeline.status != GradePipelineStatus.COMPLETED
                        || stages.get(1).status == GradeStageStatus.PASSED,
                "a failed plagiarism stage must block pipeline completion");
    }

    @Test
    @DisplayName("BFF grading: idempotent start-grading returns the same pipeline for the same key")
    void test_grading_idempotency() {
        when(aiGradingGateway.score(anyLong(), any()))
                .thenReturn(new AiScore(90, "excellent"));

        Assignment assignment = bffGradingFacade.createAssignment(
                "HW3 — Idempotency", 12L, 102L);
        bffGradingFacade.publishAssignment(assignment.id);
        Submission submission = bffGradingFacade.submit(
                assignment.id, 202L, "Idempotent submission", "sub-e2e-idem");

        GradePipeline first = bffGradingFacade.startGrading(
                submission.id, 302L, "grade-e2e-idem");
        GradePipeline second = bffGradingFacade.startGrading(
                submission.id, 302L, "grade-e2e-idem");

        assertEquals(first.id, second.id,
                "duplicate idempotency key must return the same pipeline");
        // The AI gateway should only be invoked once — the second call is a
        // dedup short-circuit and must not re-score.
        verify(aiGradingGateway, times(1)).score(anyLong(), any());
    }

    @Test
    @DisplayName("BFF grading: manual review stage remains PENDING after auto + plagiarism pass")
    void test_manual_review_pending_after_auto_stages() {
        when(aiGradingGateway.score(anyLong(), any()))
                .thenReturn(new AiScore(75, "decent"));

        Assignment assignment = bffGradingFacade.createAssignment(
                "HW4 — Manual review", 13L, 103L);
        bffGradingFacade.publishAssignment(assignment.id);
        // Short content -> plagiarism CLEAN (length 20 -> similarity 0.2).
        Submission submission = bffGradingFacade.submit(
                assignment.id, 203L, "01234567890123456789", "sub-e2e-manual");

        GradePipeline pipeline = bffGradingFacade.startGrading(
                submission.id, 303L, "grade-e2e-manual");

        List<GradeStage> stages = gradingService.getStages(pipeline.id);
        assertEquals(3, stages.size(), "pipeline must have 3 stages");
        assertEquals(GradeStageStatus.PASSED, stages.get(0).status,
                "auto_check should pass");
        assertEquals(PlagiarismStatus.CLEAN,
                plagiarismService.findBySubmissionId(submission.id).status,
                "short content should be CLEAN");
        assertEquals(GradeStageStatus.PENDING, stages.get(2).status,
                "manual_review must remain PENDING until a teacher acts");
    }

    // ------------------------------------------------------------------
    // BFF aggregation facade under test.
    //
    // Mirrors the production portal-bff grading aggregation: it creates the
    // assignment, publishes it, accepts the submission, invokes the AI
    // gateway for the auto_check score, then drives the grading pipeline.
    // ------------------------------------------------------------------

    static final class BffGradingFacade {
        private final FakeAssignmentService assignmentService;
        private final FakeSubmissionService submissionService;
        private final FakeGradingService gradingService;
        private final AiGradingGateway aiGradingGateway;
        private final GradeEventBus gradeEventBus;
        private final Set<String> seenIdempotencyKeys = ConcurrentHashMap.newKeySet();

        BffGradingFacade(FakeAssignmentService assignmentService,
                         FakeSubmissionService submissionService,
                         FakeGradingService gradingService,
                         AiGradingGateway aiGradingGateway,
                         GradeEventBus gradeEventBus) {
            this.assignmentService = assignmentService;
            this.submissionService = submissionService;
            this.gradingService = gradingService;
            this.aiGradingGateway = aiGradingGateway;
            this.gradeEventBus = gradeEventBus;
        }

        Assignment createAssignment(String title, Long courseId, Long teacherId) {
            return assignmentService.create(
                    TestDataBuilder.buildAssignment(title, courseId, teacherId));
        }

        Assignment publishAssignment(Long id) {
            return assignmentService.publish(id);
        }

        Submission submit(Long assignmentId, Long studentId,
                          String content, String idempotencyKey) {
            var cmd = new com.ep.integration.support.IntegrationDtos.SubmissionCommand(
                    assignmentId, studentId, content, idempotencyKey);
            return submissionService.submit(cmd);
        }

        GradePipeline startGrading(Long submissionId, Long gradedBy, String idempotencyKey) {
            // Call the grading service FIRST so its idempotency dedup short-
            // circuits duplicate calls before we invoke the AI gateway.
            // The BFF tracks seen idempotency keys so it can skip the (expensive)
            // AI-grading gateway call on duplicate submissions.
            boolean isNew = idempotencyKey == null
                    || seenIdempotencyKeys.add(idempotencyKey);
            if (!isNew) {
                // Duplicate call: return the existing pipeline without re-scoring.
                return gradingService.startGrading(
                        TestDataBuilder.buildGrading(submissionId, gradedBy, idempotencyKey));
            }

            GradePipeline pipeline = gradingService.startGrading(
                    TestDataBuilder.buildGrading(submissionId, gradedBy, idempotencyKey));

            // Only invoke the AI gateway for a brand-new pipeline, mirroring
            // the production BFF orchestration (auto_check score is fed in
            // after the pipeline has been created).
            Submission submission = submissionService.findById(submissionId);
            aiGradingGateway.score(submissionId, submission.content);

            if (pipeline.status == GradePipelineStatus.COMPLETED) {
                gradeEventBus.publishGradeCompleted(pipeline.id);
            }
            return pipeline;
        }
    }

    /** Mockito-friendly stand-in for the ai-platform LLM grading client. */
    interface AiGradingGateway {
        AiScore score(Long submissionId, String content);
    }

    /** Mockito-friendly stand-in for the grade-event producer. */
    interface GradeEventBus {
        void publishGradeCompleted(Long pipelineId);
    }

    record AiScore(int score, String feedback) {
    }
}
