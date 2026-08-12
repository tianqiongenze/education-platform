package com.ep.grading.homework;

import com.ep.grading.homework.entity.AssignmentStatus;
import com.ep.grading.homework.entity.PlagiarismStatus;
import com.ep.grading.homework.entity.SubmissionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnumTests {

    @Test
    void assignment_status_from_string() {
        assertEquals(AssignmentStatus.DRAFT, AssignmentStatus.fromString("draft"));
        assertEquals(AssignmentStatus.PUBLISHED, AssignmentStatus.fromString("PUBLISHED"));
        assertThrows(IllegalArgumentException.class, () -> AssignmentStatus.fromString("bogus"));
        assertThrows(IllegalArgumentException.class, () -> AssignmentStatus.fromString(null));
    }

    @Test
    void submission_status_from_string() {
        assertEquals(SubmissionStatus.SUBMITTED, SubmissionStatus.fromString("submitted"));
        assertEquals(SubmissionStatus.GRADED, SubmissionStatus.fromString("GRADED"));
        assertThrows(IllegalArgumentException.class, () -> SubmissionStatus.fromString("nope"));
        assertThrows(IllegalArgumentException.class, () -> SubmissionStatus.fromString(null));
    }

    @Test
    void plagiarism_status_values() {
        assertEquals(4, PlagiarismStatus.values().length);
        assertEquals(PlagiarismStatus.CLEAN, PlagiarismStatus.valueOf("CLEAN"));
    }
}
