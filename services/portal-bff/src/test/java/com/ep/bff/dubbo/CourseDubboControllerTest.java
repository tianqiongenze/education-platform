package com.ep.bff.dubbo;

import com.ep.business.course.Course;
import com.ep.business.course.CourseService;
import com.ep.business.course.CourseStatus;
import com.ep.business.course.CreateCourseCmd;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-setup MockMvc tests for {@link CourseDubboController}. The
 * {@code @DubboReference} field is injected via {@link ReflectionTestUtils}.
 */
class CourseDubboControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private CourseService courseService;

    @BeforeEach
    void setUp() {
        courseService = mock(CourseService.class);
        CourseDubboController controller = new CourseDubboController();
        ReflectionTestUtils.setField(controller, "courseService", courseService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.ep.bff.config.ForbiddenExceptionAdvice())
                .build();
    }

    private CreateCourseCmd cmd() {
        return new CreateCourseCmd("Java Basics", "intro", 100L, 1, 2, new BigDecimal("99.00"));
    }

    private Course courseWith(Long id, String title) {
        Course c = new Course();
        c.setCourseId(id);
        c.setTitle(title);
        c.setStatus(CourseStatus.DRAFT.getCode());
        return c;
    }

    @Test
    @DisplayName("D-242 gate: create without X-Role -> 403 anonymous")
    void create_anonymousRejected() throws Exception {
        mockMvc.perform(post("/api/v1/courses")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(cmd())))
                .andExpect(status().isForbidden());
        verify(courseService, never()).createCourse(any());
    }

    @Test
    @DisplayName("D-242 gate: create with role=user -> 403")
    void create_userRoleRejected() throws Exception {
        mockMvc.perform(post("/api/v1/courses")
                        .header("X-Role", "user")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(cmd())))
                .andExpect(status().isForbidden());
        verify(courseService, never()).createCourse(any());
    }

    @Test
    @DisplayName("D-242 gate: create with role=teacher -> passes gate")
    void create_teacherAllowed() throws Exception {
        when(courseService.createCourse(any())).thenReturn(courseWith(1L, "t"));
        mockMvc.perform(post("/api/v1/courses")
                        .header("X-Role", "teacher")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(cmd())))
                .andExpect(status().isOk());
    }

    @Test
    void create_endpoint() throws Exception {
        when(courseService.createCourse(any())).thenReturn(courseWith(1L, "Java Basics"));

        mockMvc.perform(post("/api/v1/courses")
                        .header("X-Role", "teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(1))
                .andExpect(jsonPath("$.title").value("Java Basics"));
    }

    @Test
    void getById_endpoint() throws Exception {
        when(courseService.getCourseById(1L)).thenReturn(courseWith(1L, "Spring"));

        mockMvc.perform(get("/api/v1/courses/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(1));
    }

    @Test
    void getById_notFound() throws Exception {
        when(courseService.getCourseById(999L)).thenReturn(null);

        mockMvc.perform(get("/api/v1/courses/{id}", 999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_endpoint() throws Exception {
        when(courseService.listCourses(any(), any()))
                .thenReturn(List.of(courseWith(1L, "A"), courseWith(2L, "B")));

        mockMvc.perform(get("/api/v1/courses").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].courseId").value(1))
                .andExpect(jsonPath("$[1].courseId").value(2));
    }

    @Test
    void search_endpoint() throws Exception {
        when(courseService.searchCourses(eq("Spring")))
                .thenReturn(List.of(courseWith(7L, "Spring Boot")));

        mockMvc.perform(get("/api/v1/courses/search").param("keyword", "Spring"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].courseId").value(7));
    }

    @Test
    void publish_endpoint() throws Exception {
        mockMvc.perform(post("/api/v1/courses/{id}/publish", 1L).header("X-Role", "teacher"))
                .andExpect(status().isNoContent());

        verify(courseService).publishCourse(1L);
    }

    @Test
    void archive_endpoint() throws Exception {
        mockMvc.perform(post("/api/v1/courses/{id}/archive", 1L).header("X-Role", "teacher"))
                .andExpect(status().isNoContent());

        verify(courseService).archiveCourse(1L);
    }
}
