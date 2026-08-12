package com.ep.integration;

import com.ep.integration.builder.TestDataBuilder;
import com.ep.integration.support.ItBackendFakes;
import com.ep.integration.support.ItBackendFakes.FakeAssignmentService;
import com.ep.integration.support.ItBackendFakes.FakeGradingService;
import com.ep.integration.support.ItBackendFakes.FakePlagiarismService;
import com.ep.integration.support.ItBackendFakes.FakeSubmissionService;
import com.ep.integration.support.IntegrationDtos.AssignmentStatus;
import com.ep.integration.support.IntegrationDtos.GradePipelineStatus;
import com.ep.integration.support.IntegrationDtos.GradeStageStatus;
import com.ep.integration.support.IntegrationDtos.PlagiarismStatus;
import com.ep.integration.support.IntegrationDtos.SubmissionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IT backend (grading-service) flow tests covering assignment lifecycle,
 * submission idempotency, the 3-stage grading pipeline, and plagiarism
 * detection behaviour.
 */
class ItBackendFlowIntegrationTest {

    private FakeAssignmentService assignmentService;
    private FakeSubmissionService submissionService;
    private FakePlagiarismService plagiarismService;
    private FakeGradingService gradingService;

    @BeforeEach
    void setUp() {
        assignmentService = new FakeAssignmentService();
        submissionService = new FakeSubmissionService();
        plagiarismService = new FakePlagiarismService(submissionService);
        gradingService = new FakeGradingService(plagiarismService);
    }

    @Test
    @DisplayName("homework flow: create -> publish -> submit -> grade -> GRADED")
    void test_homework_complete_flow() {
        var assignment = assignmentService.create(
                TestDataBuilder.buildAssignment("HW1 — Spring Boot", 10L, 100L));
        assertEquals(AssignmentStatus.DRAFT, assignment.status);

        assignmentService.publish(assignment.id);
        assertEquals(AssignmentStatus.PUBLISHED, assignmentService.findById(assignment.id).status);

        var submission = submissionService.submit(
                TestDataBuilder.buildSubmission(assignment.id, 200L, "sub-idem-1"));
        assertEquals(SubmissionStatus.SUBMITTED, submission.status);

        var graded = submissionService.grade(submission.id, 90, "Good work", "grade-idem-1");
        assertEquals(SubmissionStatus.GRADED, graded.status);
        assertEquals(90, graded.score);
        assertEquals(1, graded.version);
    }

    @Test
    @DisplayName("submission idempotency: same key returns the same submission")
    void test_submission_idempotency() {
        String key = "sub-idem-dup";
        var first = submissionService.submit(
                TestDataBuilder.buildSubmission(1L, 200L, key));
        var second = submissionService.submit(
                TestDataBuilder.buildSubmission(1L, 200L, key));

        assertEquals(first.id, second.id);
        assertEquals(first.idempotencyKey, second.idempotencyKey);
    }

    @Test
    @DisplayName("grading pipeline runs 3 stages and reaches COMPLETED")
    void test_grading_pipeline() {
        var submission = submissionService.submit(
                TestDataBuilder.buildSubmission(1L, 200L, "sub-pipeline"));
        var pipeline = gradingService.startGrading(
                TestDataBuilder.buildGrading(submission.id, 300L, "grade-pipeline-1"));

        assertNotNull(pipeline.id);
        assertEquals(GradePipelineStatus.COMPLETED, pipeline.status);

        List<ItBackendFakes.GradeStage> stages = gradingService.getStages(pipeline.id);
        assertEquals(3, stages.size(), "pipeline must have 3 stages");
        assertEquals("auto_check", stages.get(0).stageName);
        assertEquals(GradeStageStatus.PASSED, stages.get(0).status);
        assertEquals("plagiarism_check", stages.get(1).stageName);
        assertEquals(GradeStageStatus.PASSED, stages.get(1).status);
        assertEquals("manual_review", stages.get(2).stageName);
        assertEquals(GradeStageStatus.PENDING, stages.get(2).status);

        assertTrue(pipeline.totalScore >= 40, "total score should include auto_check score");
    }

    @Test
    @DisplayName("plagiarism check: short content -> CLEAN, long content -> FLAGGED or REVIEWING")
    void test_plagiarism_check() {
        // Short content: length 60 -> 60 % 100 = 60 -> similarity 0.6 -> REVIEWING
        // Need length with length % 100 < 30 for CLEAN. Use a 20-char content.
        var shortSub = submissionService.submit(TestDataBuilder.buildSubmission(1L, 200L, "sub-short"));
        shortSub.content = "01234567890123456789"; // length 20 -> similarity 0.2 -> CLEAN
        var shortCheck = plagiarismService.check(shortSub.id);
        assertEquals(PlagiarismStatus.CLEAN, shortCheck.status);

        // Long content: length 170 -> 170 % 100 = 70 -> similarity 0.7 -> FLAGGED
        var longSub = submissionService.submit(TestDataBuilder.buildSubmission(1L, 201L, "sub-long"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 170; i++) sb.append('x');
        longSub.content = sb.toString();
        var longCheck = plagiarismService.check(longSub.id);
        assertTrue(longCheck.status == PlagiarismStatus.FLAGGED
                        || longCheck.status == PlagiarismStatus.REVIEWING,
                "long content should be FLAGGED or REVIEWING, got " + longCheck.status);
    }
}
