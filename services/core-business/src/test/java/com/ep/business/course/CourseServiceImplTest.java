package com.ep.business.course;

import com.ep.business.event.PulsarClientProperties;
import com.ep.business.event.PulsarEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CourseServiceImplTest {

    private CourseRepository repository;
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
        service = new CourseServiceImpl(repository, noopPublisher());
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
    }

    @Test
    void createCourse_blankTitleRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.createCourse(cmd("  ")));
    }

    @Test
    void getCourseById_found() {
        Course c = service.createCourse(cmd("Python"));
        when(repository.findById(c.getCourseId())).thenReturn(Optional.of(c));
        Course found = service.getCourseById(c.getCourseId());
        assertNotNull(found);
        assertEquals(c.getCourseId(), found.getCourseId());
    }

    @Test
    void getCourseById_notFound() {
        when(repository.findById(99999L)).thenReturn(Optional.empty());
        assertNull(service.getCourseById(99999L));
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
    }

    @Test
    void archiveCourse_setsArchived() {
        Course c = service.createCourse(cmd("To Archive"));
        when(repository.findById(c.getCourseId())).thenReturn(Optional.of(c));
        service.publishCourse(c.getCourseId());
        service.archiveCourse(c.getCourseId());
        assertEquals(CourseStatus.ARCHIVED.getCode(), c.getStatus());
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
