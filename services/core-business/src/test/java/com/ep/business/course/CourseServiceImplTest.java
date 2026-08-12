package com.ep.business.course;

import com.ep.business.event.PulsarClientProperties;
import com.ep.business.event.PulsarEventPublisher;
import com.ep.cache.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CourseServiceImplTest {

    private CourseRepository repository;
    private CacheService cacheService;
    private CourseServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(CourseRepository.class);
        when(repository.save(any(Course.class))).thenAnswer(inv -> {
            Course c = inv.getArgument(0);
            if (c.getCourseId() == null) {
                c.setCourseId(System.nanoTime() % 100000L);
            }
            return c;
        });
        cacheService = mock(CacheService.class);
        // By default, simulate a cache miss: invoke the loader so the repository is called.
        when(cacheService.getOrLoad(anyString(), anyString(), any(), anyLong(), anyBoolean()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> loader = inv.getArgument(2);
                    return loader.get();
                });
        service = new CourseServiceImpl(repository, noopPublisher(), cacheService);
    }

    /** A disabled PulsarEventPublisher that never touches Pulsar. */
    private static PulsarEventPublisher noopPublisher() {
        ObjectProvider<org.apache.pulsar.client.api.PulsarClient> cp = mock(ObjectProvider.class);
        when(cp.getIfAvailable()).thenReturn(null);
        PulsarClientProperties props = new PulsarClientProperties();
        PulsarEventPublisher pub = new PulsarEventPublisher(cp, props, false);
        pub.init();
        return pub;
    }

    private CreateCourseCmd cmd(String title) {
        return new CreateCourseCmd(title, "desc", 100L, 1, 2, new BigDecimal("99.00"));
    }

    @Test
    void createCourse_success() {
        Course c = service.createCourse(cmd("Java Basics"));
        assertNotNull(c.getCourseId());
        assertEquals("Java Basics", c.getTitle());
        assertEquals(CourseStatus.DRAFT.getCode(), c.getStatus());
        assertEquals(0, c.getStudentCount());
        assertNotNull(c.getCreatedAt());
        // D-182: create should evict the list cache.
        verify(cacheService).evict("course:list", "all");
    }

    @Test
    void createCourse_blankTitleRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.createCourse(cmd("  ")));
        verify(cacheService, never()).evict(anyString(), anyString());
    }

    @Test
    void getCourseById_found() {
        Course c = service.createCourse(cmd("Python"));
        when(repository.findById(c.getCourseId())).thenReturn(Optional.of(c));
        Course found = service.getCourseById(c.getCourseId());
        assertNotNull(found);
        assertEquals(c.getCourseId(), found.getCourseId());
        // D-182: getCourseById should go through cache with cacheEmpty=true.
        verify(cacheService).getOrLoad(eq("course"), eq(String.valueOf(c.getCourseId())), any(), eq(300L), eq(true));
    }

    @Test
    void getCourseById_notFound() {
        when(repository.findById(99999L)).thenReturn(Optional.empty());
        assertNull(service.getCourseById(99999L));
        // D-182: null results are cached (cacheEmpty=true) to prevent cache penetration.
        verify(cacheService).getOrLoad(eq("course"), eq("99999"), any(), eq(300L), eq(true));
    }

    @Test
    void getCourseById_cacheHitReturnsCachedValue() {
        // Simulate a cache hit — getOrLoad returns a cached course without calling the loader.
        Course cached = new Course();
        cached.setCourseId(42L);
        when(cacheService.getOrLoad(eq("course"), eq("42"), any(), anyLong(), anyBoolean()))
                .thenReturn(cached);
        Course result = service.getCourseById(42L);
        assertEquals(42L, result.getCourseId());
        // Repository should NOT be called on a cache hit.
        verify(repository, never()).findById(42L);
    }

    @Test
    void listCourses_pagination() {
        List<Course> first10 = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> {
                    Course c = new Course();
                    c.setCourseId((long) i);
                    return c;
                })
                .toList();
        List<Course> next5 = java.util.stream.IntStream.range(10, 15)
                .mapToObj(i -> {
                    Course c = new Course();
                    c.setCourseId((long) i);
                    return c;
                })
                .toList();
        when(repository.findAllByOrderByCourseIdAsc(any()))
                .thenReturn(new PageImpl<>(first10, PageRequest.of(0, 10), 15))
                .thenReturn(new PageImpl<>(next5, PageRequest.of(1, 10), 15));
        List<Course> page1 = service.listCourses(1, 10);
        List<Course> page2 = service.listCourses(2, 10);
        assertEquals(10, page1.size());
        assertEquals(5, page2.size());
        // D-182: list goes through cache.
        verify(cacheService, times(2)).getOrLoad(eq("course:list"), eq("all"), any(), eq(300L), eq(false));
    }

    @Test
    void listCourses_emptyPage() {
        when(repository.findAllByOrderByCourseIdAsc(any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(4, 10), 1));
        List<Course> empty = service.listCourses(5, 10);
        assertTrue(empty.isEmpty());
    }

    @Test
    void searchCourses_matchesTitleContains() {
        when(repository.findByTitleContainingOrderByCourseIdAsc("Spring"))
                .thenReturn(List.of(
                        courseWith(1L, "Spring Boot Guide"),
                        courseWith(2L, "Advanced Spring")));
        List<Course> result = service.searchCourses("Spring");
        assertEquals(2, result.size());
    }

    @Test
    void searchCourses_noMatch() {
        when(repository.findByTitleContainingOrderByCourseIdAsc("Physics"))
                .thenReturn(List.of());
        List<Course> result = service.searchCourses("Physics");
        assertTrue(result.isEmpty());
    }

    @Test
    void publishCourse_setsPublished() {
        Course c = service.createCourse(cmd("To Publish"));
        when(repository.findById(c.getCourseId())).thenReturn(Optional.of(c));
        service.publishCourse(c.getCourseId());
        assertEquals(CourseStatus.PUBLISHED.getCode(), c.getStatus());
        // D-182: publish should evict both the course and list caches.
        verify(cacheService).evict("course", String.valueOf(c.getCourseId()));
        verify(cacheService, atLeastOnce()).evict("course:list", "all");
    }

    @Test
    void archiveCourse_setsArchived() {
        Course c = service.createCourse(cmd("To Archive"));
        when(repository.findById(c.getCourseId())).thenReturn(Optional.of(c));
        service.publishCourse(c.getCourseId());
        service.archiveCourse(c.getCourseId());
        assertEquals(CourseStatus.ARCHIVED.getCode(), c.getStatus());
        // D-182: archive should evict both the course and list caches (publish also evicted).
        verify(cacheService, atLeastOnce()).evict("course", String.valueOf(c.getCourseId()));
    }

    @Test
    void publishCourse_notFoundThrows() {
        when(repository.findById(99999L)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.publishCourse(99999L));
    }

    private Course courseWith(Long id, String title) {
        Course c = new Course();
        c.setCourseId(id);
        c.setTitle(title);
        return c;
    }
}
