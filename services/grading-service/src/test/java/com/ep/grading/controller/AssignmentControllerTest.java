package com.ep.grading.controller;

import com.ep.grading.homework.dto.AssignmentCommand;
import com.ep.grading.homework.entity.Assignment;
import com.ep.grading.homework.entity.AssignmentStatus;
import com.ep.grading.homework.service.AssignmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssignmentControllerTest {

    private MockMvc mockMvc;
    private AssignmentService assignmentService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() throws Exception {
        assignmentService = mock(AssignmentService.class);
        AssignmentController controller = new AssignmentController();
        java.lang.reflect.Field f = AssignmentController.class.getDeclaredField("assignmentService");
        f.setAccessible(true);
        f.set(controller, assignmentService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void test_create_assignment_endpoint() throws Exception {
        Assignment assignment = new Assignment(1L, "HW1", "Desc", 10L, 20L, 100,
                LocalDateTime.now().plusDays(1), AssignmentStatus.DRAFT, LocalDateTime.now(), null);
        when(assignmentService.create(any(AssignmentCommand.class))).thenReturn(assignment);

        AssignmentCommand cmd = new AssignmentCommand("HW1", "Desc", 10L, 20L, 100,
                LocalDateTime.now().plusDays(1));
        mockMvc.perform(post("/api/v1/assignments")
                        .header("X-Role", "teacher")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cmd)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("HW1"))
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void test_get_assignment_endpoint() throws Exception {
        Assignment assignment = new Assignment(1L, "HW1", "Desc", 10L, 20L, 100,
                null, AssignmentStatus.PUBLISHED, LocalDateTime.now(), null);
        when(assignmentService.findById(1L)).thenReturn(assignment);
        when(assignmentService.findByCourseId(10L)).thenReturn(List.of(assignment));

        mockMvc.perform(get("/api/v1/assignments/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("HW1"));

        mockMvc.perform(get("/api/v1/assignments").param("courseId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("HW1"));
    }

    @Test
    void test_get_assignment_not_found() throws Exception {
        when(assignmentService.findById(99L)).thenReturn(null);
        mockMvc.perform(get("/api/v1/assignments/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void test_publish_endpoint() throws Exception {
        Assignment published = new Assignment(1L, "HW1", "Desc", 10L, 20L, 100,
                null, AssignmentStatus.PUBLISHED, LocalDateTime.now(), null);
        when(assignmentService.publish(eq(1L))).thenReturn(published);

        mockMvc.perform(post("/api/v1/assignments/1/publish")
                        .header("X-Role", "teacher"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void test_close_endpoint() throws Exception {
        Assignment closed = new Assignment(1L, "HW1", "Desc", 10L, 20L, 100,
                null, AssignmentStatus.CLOSED, LocalDateTime.now(), null);
        when(assignmentService.close(eq(1L))).thenReturn(closed);

        mockMvc.perform(post("/api/v1/assignments/1/close")
                        .header("X-Role", "teacher"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }
}
