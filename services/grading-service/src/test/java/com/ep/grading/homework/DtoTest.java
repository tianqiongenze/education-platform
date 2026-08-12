package com.ep.grading.homework;

import com.ep.grading.homework.dto.AssignmentCommand;
import com.ep.grading.homework.dto.GradeSubmissionCommand;
import com.ep.grading.homework.dto.SubmissionCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DtoTest {

    @Test
    void assignment_command_default_max_score() {
        AssignmentCommand cmd = new AssignmentCommand("T", "D", 1L, 2L, null, null);
        assertEquals(100, cmd.maxScore());
        assertEquals("T", cmd.title());
        assertNull(cmd.deadline());
    }

    @Test
    void submission_command_accessors() {
        SubmissionCommand cmd = new SubmissionCommand(1L, 2L, "c", "k");
        assertEquals(1L, cmd.assignmentId());
        assertEquals(2L, cmd.studentId());
        assertEquals("c", cmd.content());
        assertEquals("k", cmd.idempotencyKey());
    }

    @Test
    void grade_command_accessors() {
        GradeSubmissionCommand cmd = new GradeSubmissionCommand(1L, 90, "f", 5L, "k");
        assertEquals(1L, cmd.submissionId());
        assertEquals(90, cmd.score());
        assertEquals("f", cmd.feedback());
        assertEquals(5L, cmd.graderId());
        assertEquals("k", cmd.idempotencyKey());
    }
}
