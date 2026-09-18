package com.ep.business.course;

import com.ep.business.event.PulsarClientProperties;
import com.ep.business.event.PulsarEventPublisher;
import com.ep.cache.CacheService;
import com.ep.web.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
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
        TenantContext.clear();
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
        // (P0: services now use the tenant-scoped 6-arg overload; loader is argument index 3.)
        when(cacheService.getOrLoad(anyString(), anyString(), anyString(), any(), anyLong(), anyBoolean()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> loader = inv.getArgument(3);
                    return loader.get();
                });
        service = new CourseServiceImpl(repository, noopPublisher(), cacheService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
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
        verify(cacheService).evictAll("course:list"); // D-216: unbounded pagination keys
        verify(cacheService).evictAll("course:search"); // new course can match searches
    }

    @Test
    void createCourse_blankTitleRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.createCourse(cmd("  ")));
        verify(cacheService, never()).evict(anyString(), anyString(), anyString());
    }

    @Test
    void getCourseById_found() {
        Course c = service.createCourse(cmd("Python"));
        when(repository.findById(c.getCourseId())).thenReturn(Optional.of(c));
        Course found = service.getCourseById(c.getCourseId());
        assertNotNull(found);
        assertEquals(c.getCourseId(), found.getCourseId());
        // D-182: getCourseById should go through cache with cacheEmpty=true.
        // P0: tenant dimension ("default" when no tenant context) is in the key.
        verify(cacheService).getOrLoad(eq("course"), eq("default"),
                eq(String.valueOf(c.getCourseId())), any(), eq(300L), eq(true));
    }

    @Test
    void getCourseById_notFound() {
        when(repository.findById(99999L)).thenReturn(Optional.empty());
        assertNull(service.getCourseById(99999L));
        // D-182: null results are cached (cacheEmpty=true) to prevent cache penetration.
        verify(cacheService).getOrLoad(eq("course"), eq("default"), eq("99999"), any(), eq(300L), eq(true));
    }

    @Test
    void getCourseById_cacheHitReturnsCachedValue() {
        // Simulate a cache hit — getOrLoad returns a cached course without calling the loader.
        Course cached = new Course();
        cached.setCourseId(42L);
        when(cacheService.getOrLoad(eq("course"), eq("default"), eq("42"), any(), anyLong(), anyBoolean()))
                .thenReturn(cached);
        Course result = service.getCourseById(42L);
        assertEquals(42L, result.getCourseId());
        // Repository should NOT be called on a cache hit.
        verify(repository, never()).findById(42L);
    }

    @Test
    void getCourseById_usesCurrentTenantInCacheKey() {
        // P0 (租户未进 key): the TenantContext tenant must become the cache-key tenant.
        TenantContext.setTenantId("tenant-x");
        when(repository.findById(7L)).thenReturn(Optional.of(courseWith(7L, "T")));

        service.getCourseById(7L);

        verify(cacheService).getOrLoad(eq("course"), eq("tenant-x"), eq("7"), any(), eq(300L), eq(true));
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
        // D-182: list goes through cache. D-216: each pagination has its OWN
        // key (was a single "all" — page 2 used to serve page 1's cached row).
        verify(cacheService).getOrLoad(eq("course:list"), eq("default"), eq("p1:10"), any(), eq(300L), eq(false));
        verify(cacheService).getOrLoad(eq("course:list"), eq("default"), eq("p2:10"), any(), eq(300L), eq(false));
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
        // 补齐高频未缓存读：search 结果走 30s 短 TTL 缓存（含租户维度）。
        verify(cacheService).getOrLoad(eq("course:search"), eq("default"), eq("Spring"),
                any(), eq(30L), eq(false));
    }

    @Test
    void searchCourses_noMatch() {
        when(repository.findByTitleContainingOrderByCourseIdAsc("Physics"))
                .thenReturn(List.of());
        List<Course> result = service.searchCourses("Physics");
        assertTrue(result.isEmpty());
        verify(cacheService).getOrLoad(eq("course:search"), eq("default"), eq("Physics"),
                any(), eq(30L), eq(false));
    }

    @Test
    void searchCourses_usesCurrentTenantInCacheKey() {
        TenantContext.setTenantId("tenant-y");
        when(repository.findByTitleContainingOrderByCourseIdAsc("K8s"))
                .thenReturn(List.of(courseWith(9L, "K8s 101")));

        service.searchCourses("K8s");

        verify(cacheService).getOrLoad(eq("course:search"), eq("tenant-y"), eq("K8s"),
                any(), eq(30L), eq(false));
    }

    @Test
    void publishCourse_setsPublished() {
        Course c = service.createCourse(cmd("To Publish"));
        when(repository.findById(c.getCourseId())).thenReturn(Optional.of(c));
        service.publishCourse(c.getCourseId());
        assertEquals(CourseStatus.PUBLISHED.getCode(), c.getStatus());
        // D-182: publish should evict both the course and list caches (tenant-scoped key).
        verify(cacheService).evict(eq("course"), eq("default"), eq(String.valueOf(c.getCourseId())));
        verify(cacheService, atLeastOnce()).evictAll("course:list"); // D-216
        verify(cacheService, atLeastOnce()).evictAll("course:search");
    }

    @Test
    void archiveCourse_setsArchived() {
        Course c = service.createCourse(cmd("To Archive"));
        when(repository.findById(c.getCourseId())).thenReturn(Optional.of(c));
        service.publishCourse(c.getCourseId());
        service.archiveCourse(c.getCourseId());
        assertEquals(CourseStatus.ARCHIVED.getCode(), c.getStatus());
        // D-182: archive should evict both the course and list caches (publish also evicted).
        verify(cacheService, atLeastOnce()).evict(eq("course"), eq("default"), eq(String.valueOf(c.getCourseId())));
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
