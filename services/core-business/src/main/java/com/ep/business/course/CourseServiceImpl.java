package com.ep.business.course;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.ep.business.common.DomainEvent;
import com.ep.business.common.SnowflakeIdGenerator;
import com.ep.business.event.PulsarEventPublisher;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@DubboService
public class CourseServiceImpl implements CourseService {

    private static final Logger log = LoggerFactory.getLogger(CourseServiceImpl.class);

    private final CourseRepository courseRepository;
    private final PulsarEventPublisher eventPublisher;

    public CourseServiceImpl(CourseRepository courseRepository, PulsarEventPublisher eventPublisher) {
        this.courseRepository = courseRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    @SentinelResource(value = "CourseService.createCourse",
            blockHandler = "createCourseBlockHandler", fallback = "createCourseFallback")
    public Course createCourse(CreateCourseCmd cmd) {
        Objects.requireNonNull(cmd, "cmd must not be null");
        requireNonBlank(cmd.getTitle(), "title");
        Objects.requireNonNull(cmd.getTeacherId(), "teacherId");

        long now = System.currentTimeMillis();
        Course course = new Course();
        course.setCourseId(SnowflakeIdGenerator.getInstance().nextId());
        course.setTitle(cmd.getTitle());
        course.setDescription(cmd.getDescription());
        course.setTeacherId(cmd.getTeacherId());
        course.setCategory(cmd.getCategory() == null ? 4 : cmd.getCategory());
        course.setDifficulty(cmd.getDifficulty() == null ? 1 : cmd.getDifficulty());
        course.setPrice(cmd.getPrice() == null ? BigDecimal.ZERO : cmd.getPrice());
        course.setStatus(CourseStatus.DRAFT.getCode());
        course.setStudentCount(0);
        course.setCreatedAt(now);
        course.setUpdatedAt(now);
        return courseRepository.save(course);
    }

    @Override
    @Transactional(readOnly = true)
    public Course getCourseById(Long courseId) {
        Objects.requireNonNull(courseId, "courseId must not be null");
        return courseRepository.findById(courseId).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Course> listCourses(Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : size;
        Page<Course> pageResult = courseRepository.findAllByOrderByCourseIdAsc(PageRequest.of(p - 1, s));
        return new ArrayList<>(pageResult.getContent());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Course> searchCourses(String keyword) {
        requireNonBlank(keyword, "keyword");
        return courseRepository.findByTitleContainingOrderByCourseIdAsc(keyword);
    }

    @Override
    @Transactional
    public void publishCourse(Long courseId) {
        Objects.requireNonNull(courseId, "courseId must not be null");
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalStateException("Course not found: " + courseId));
        // D-066: state transition moved into the Course aggregate.
        course.publish();
        courseRepository.save(course);
        publishEvents(course);
    }

    @Override
    @Transactional
    public void archiveCourse(Long courseId) {
        Objects.requireNonNull(courseId, "courseId must not be null");
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalStateException("Course not found: " + courseId));
        // D-066: state transition moved into the Course aggregate.
        course.archive();
        courseRepository.save(course);
        publishEvents(course);
    }

    /** Sentinel block handler — signature must match the protected method plus BlockException. */
    public Course createCourseBlockHandler(CreateCourseCmd cmd, com.alibaba.csp.sentinel.slots.block.BlockException ex) {
        throw new IllegalStateException("Course creation rate-limited: " + ex.getClass().getSimpleName());
    }

    /** Sentinel fallback — used when the resource or downstream throws. */
    public Course createCourseFallback(CreateCourseCmd cmd, Throwable t) {
        throw new IllegalStateException("Course creation unavailable: " + t.getMessage());
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    /**
     * D-066: pull recorded domain events from the aggregate and publish them via Pulsar.
     * Best-effort: a publish failure is logged by {@link PulsarEventPublisher} and never
     * rolls back the surrounding transaction.
     */
    private void publishEvents(Course course) {
        List<DomainEvent> events = course.pullEvents();
        for (DomainEvent event : events) {
            log.info("D-066: domain event recorded: {}", event);
            eventPublisher.publish(event);
        }
    }
}
