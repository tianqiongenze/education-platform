package com.ep.grading.homework;

import com.ep.grading.homework.entity.Assignment;
import com.ep.grading.homework.entity.PlagiarismCheck;
import com.ep.grading.homework.entity.Submission;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HomeworkEntityTest {

    @Test
    void assignment_all_args_and_setters() {
        Assignment a = new Assignment();
        a.setId(1L);
        a.setTitle("T");
        a.setDescription("D");
        a.setCourseId(2L);
        a.setTeacherId(3L);
        a.setMaxScore(50);
        a.setStatus(null);
        assertEquals(1L, a.getId());
        assertEquals("T", a.getTitle());
        assertEquals(50, a.getMaxScore());
        Assignment other = new Assignment(1L, "x", null, null, null, null, null, null, null, null);
        assertEquals(a, other);
        assertEquals(a.hashCode(), other.hashCode());
    }

    @Test
    void submission_setters_and_equals() {
        Submission s = new Submission();
        s.setId(1L);
        s.setAssignmentId(2L);
        s.setStudentId(3L);
        s.setContent("c");
        s.setScore(10);
        s.setFeedback("f");
        s.setIdempotencyKey("k");
        s.setVersion(1);
        assertEquals(1L, s.getId());
        assertEquals(2L, s.getAssignmentId());
        assertEquals("c", s.getContent());
        assertEquals(10, s.getScore());
        assertEquals("f", s.getFeedback());
        assertEquals(1, s.getVersion());
        Submission other = new Submission();
        other.setId(1L);
        assertEquals(s, other);
    }

    @Test
    void plagiarism_check_setters() {
        PlagiarismCheck c = new PlagiarismCheck();
        c.setId(1L);
        c.setSubmissionId(2L);
        c.setSimilarityScore(0.5);
        c.setMatchedSources("[]");
        assertEquals(1L, c.getId());
        assertEquals(0.5, c.getSimilarityScore());
        assertNotNull(c);
    }

    @Test
    void assignment_default_max_score() {
        Assignment a = new Assignment(1L, "t", null, null, null, null, null, null, null, null);
        assertEquals(100, a.getMaxScore());
    }
}
