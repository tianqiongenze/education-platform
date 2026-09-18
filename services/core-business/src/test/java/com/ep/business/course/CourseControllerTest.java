package com.ep.business.course;

import com.ep.business.event.PulsarClientProperties;
import com.ep.business.event.PulsarEventPublisher;
import com.ep.cache.CacheService;
import com.ep.web.common.GlobalExceptionHandler;
import com.ep.web.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-setup MockMvc tests for {@link CourseController}. Mirrors the
 * canonical {@code UserControllerTest} pattern: real {@link CourseServiceImpl}
 * wired over a mocked {@link CourseRepository}, with a no-op Pulsar publisher
 * so no event bus infra is required.
 */
class CourseControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private CourseRepository repository;
    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        TenantContext.clear(); // deterministic tenant ("default") for cache keys
        repository = mock(CourseRepository.class);
        when(repository.save(any(Course.class))).thenAnswer(inv -> {
            Course c = inv.getArgument(0);
            if (c.getCourseId() == null) {
                c.setCourseId(System.nanoTime() % 100000L);
            }
            return c;
        });
        cacheService = mock(CacheService.class);
        // Simulate cache miss — loader is invoked, so the repository is exercised.
        // (P0: CourseServiceImpl now uses the tenant-scoped 6-arg overload; loader at index 3.)
        when(cacheService.getOrLoad(anyString(), anyString(), anyString(), any(), anyLong(), anyBoolean()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> loader = inv.getArgument(3);
                    return loader.get();
                });
        CourseService courseService = new CourseServiceImpl(repository, noopPublisher(), cacheService);
        CourseController controller = new CourseController(courseService);
        // standaloneSetup does not auto-register @RestControllerAdvice, so wire
        // the shared GlobalExceptionHandler explicitly to get the real 403 mapping
        // for ForbiddenException thrown by RoleChecker.
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A disabled PulsarEventPublisher that never touches Pulsar. */
    @SuppressWarnings("unchecked")
    private static PulsarEventPublisher noopPublisher() {
        ObjectProvider<org.apache.pulsar.client.api.PulsarClient> cp = mock(ObjectProvider.class);
        when(cp.getIfAvailable()).thenReturn(null);
        PulsarClientProperties props = new PulsarClientProperties();
        PulsarEventPublisher pub = new PulsarEventPublisher(cp, props, false);
        pub.init();
        return pub;
    }

    private CreateCourseCmd cmd() {
        return new CreateCourseCmd("Java Basics", "intro course", 100L, 1, 2, new BigDecimal("99.00"));
    }

    @Test
    void createCourse_endpoint() throws Exception {
        mockMvc.perform(post("/api/v1/courses")
                        .header("X-Role", "teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Java Basics"))
                .andExpect(jsonPath("$.teacherId").value(100))
                .andExpect(jsonPath("$.status").value(CourseStatus.DRAFT.getCode()))
                .andExpect(jsonPath("$.studentCount").value(0));
    }

    @Test
    void createCourse_forbiddenWithoutRole() throws Exception {
        // No X-Role header → RoleChecker#requireRole throws ForbiddenException.
        mockMvc.perform(post("/api/v1/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd())))
                .andExpect(status().isForbidden());
    }

    @Test
    void getById_endpoint() throws Exception {
        Course created = new CourseServiceImpl(repository, noopPublisher(), cacheService).createCourse(cmd());
        when(repository.findById(created.getCourseId())).thenReturn(Optional.of(created));

        mockMvc.perform(get("/api/v1/courses/{id}", created.getCourseId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(created.getCourseId().intValue()))
                .andExpect(jsonPath("$.title").value("Java Basics"));
    }

    @Test
    void getById_notFound() throws Exception {
        when(repository.findById(404L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/courses/{id}", 404L))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_endpoint() throws Exception {
        Course a = courseWith(1L, "Spring");
        Course b = courseWith(2L, "Trino");
        when(repository.findAllByOrderByCourseIdAsc(any()))
                .thenReturn(new PageImpl<>(List.of(a, b), PageRequest.of(0, 10), 2));

        mockMvc.perform(get("/api/v1/courses").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].courseId").value(1))
                .andExpect(jsonPath("$[1].courseId").value(2));
    }

    @Test
    void search_endpoint() throws Exception {
        when(repository.findByTitleContainingOrderByCourseIdAsc("Spring"))
                .thenReturn(List.of(courseWith(7L, "Spring Boot")));

        mockMvc.perform(get("/api/v1/courses/search").param("keyword", "Spring"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].courseId").value(7))
                .andExpect(jsonPath("$[0].title").value("Spring Boot"));
    }

    @Test
    void publishCourse_endpoint() throws Exception {
        Course created = new CourseServiceImpl(repository, noopPublisher(), cacheService).createCourse(cmd());
        when(repository.findById(created.getCourseId())).thenReturn(Optional.of(created));

        mockMvc.perform(post("/api/v1/courses/{id}/publish", created.getCourseId())
                        .header("X-Role", "admin"))
                .andExpect(status().isNoContent());
    }

    @Test
    void archiveCourse_endpoint() throws Exception {
        // Build a PUBLISHED course so the archive transition is valid.
        Course created = new CourseServiceImpl(repository, noopPublisher(), cacheService).createCourse(cmd());
        created.publish();
        when(repository.findById(created.getCourseId())).thenReturn(Optional.of(created));

        mockMvc.perform(post("/api/v1/courses/{id}/archive", created.getCourseId())
                        .header("X-Role", "teacher"))
                .andExpect(status().isNoContent());
    }

    private Course courseWith(Long id, String title) {
        Course c = new Course();
        c.setCourseId(id);
        c.setTitle(title);
        return c;
    }
}
