package com.ep.grading.controller;

import com.ep.grading.homework.dto.GradeSubmissionCommand;
import com.ep.grading.homework.dto.SubmissionCommand;
import com.ep.grading.homework.entity.Submission;
import com.ep.grading.homework.entity.SubmissionStatus;
import com.ep.grading.homework.service.SubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SubmissionControllerTest {

    private MockMvc mockMvc;
    private SubmissionService submissionService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() throws Exception {
        submissionService = mock(SubmissionService.class);
        SubmissionController controller = new SubmissionController();
        java.lang.reflect.Field f = SubmissionController.class.getDeclaredField("submissionService");
        f.setAccessible(true);
        f.set(controller, submissionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void test_submit_endpoint() throws Exception {
        Submission submission = new Submission();
        submission.setId(1L);
        submission.setAssignmentId(10L);
        submission.setStudentId(20L);
        submission.setContent("content");
        submission.setStatus(SubmissionStatus.SUBMITTED);
        submission.setSubmittedAt(LocalDateTime.now());
        when(submissionService.submit(any(SubmissionCommand.class))).thenReturn(submission);

        SubmissionCommand cmd = new SubmissionCommand(10L, 20L, "content", "key");
        mockMvc.perform(post("/api/v1/submissions")
                        .header("X-Role", "student")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cmd)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.assignmentId").value(10));
    }

    @Test
    void test_grade_endpoint() throws Exception {
        Submission graded = new Submission();
        graded.setId(1L);
        graded.setScore(90);
        graded.setFeedback("Good");
        graded.setStatus(SubmissionStatus.GRADED);
        when(submissionService.grade(any(GradeSubmissionCommand.class))).thenReturn(graded);

        GradeSubmissionCommand cmd = new GradeSubmissionCommand(1L, 90, "Good", 5L, "g-key");
        mockMvc.perform(post("/api/v1/submissions/1/grade")
                        .header("X-Role", "teacher")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(90))
                .andExpect(jsonPath("$.status").value("GRADED"));
    }

    @Test
    void test_find_by_id_endpoint() throws Exception {
        Submission s = new Submission();
        s.setId(1L);
        s.setAssignmentId(10L);
        when(submissionService.findById(1L)).thenReturn(s);

        mockMvc.perform(get("/api/v1/submissions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void test_find_by_id_not_found() throws Exception {
        when(submissionService.findById(99L)).thenReturn(null);
        mockMvc.perform(get("/api/v1/submissions/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void test_find_by_assignment_id_endpoint() throws Exception {
        Submission s = new Submission();
        s.setId(1L);
        s.setAssignmentId(10L);
        when(submissionService.findByAssignmentId(10L)).thenReturn(List.of(s));

        mockMvc.perform(get("/api/v1/submissions").param("assignmentId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assignmentId").value(10));
    }
}
