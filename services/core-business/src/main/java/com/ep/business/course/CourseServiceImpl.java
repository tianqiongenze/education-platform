package com.ep.business.course;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.ep.business.common.DomainEvent;
import com.ep.business.common.SnowflakeIdGenerator;
import com.ep.business.event.PulsarEventPublisher;
import com.ep.cache.CacheService;
import com.ep.web.common.tenant.TenantContext;
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

    /** D-182: cache names and TTLs for the course domain. */
    private static final String CACHE_COURSE = "course";
    private static final String CACHE_COURSE_LIST = "course:list";
    private static final String CACHE_COURSE_SEARCH = "course:search";
    private static final String LIST_ALL_KEY = "all";
    private static final long COURSE_TTL_SEC = 300;
    private static final long COURSE_LIST_TTL_SEC = 300;
    /** P0 补齐高频未缓存读：search 结果短 TTL 缓存（30s）。 */
    private static final long COURSE_SEARCH_TTL_SEC = 30;

    private final CourseRepository courseRepository;
    private final PulsarEventPublisher eventPublisher;
    private final CacheService cacheService;

    public CourseServiceImpl(CourseRepository courseRepository, PulsarEventPublisher eventPublisher,
                             CacheService cacheService) {
        this.courseRepository = courseRepository;
        this.eventPublisher = eventPublisher;
        this.cacheService = cacheService;
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
        Course saved = courseRepository.save(course);
        // D-182: evict list cache since a new course changes the list.
        cacheService.evictAll(CACHE_COURSE_LIST); // D-216: pagination keys are unbounded — evict whole cache
        cacheService.evictAll(CACHE_COURSE_SEARCH); // new course may match existing search keywords
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Course getCourseById(Long courseId) {
        Objects.requireNonNull(courseId, "courseId must not be null");
        // P0 (audit: 租户未进 key): tenant dimension from TenantContext is part
        // of the cache key so two tenants can never read each other's entry.
        return cacheService.getOrLoad(CACHE_COURSE, currentTenant(), String.valueOf(courseId),
                () -> courseRepository.findById(courseId).orElse(null), COURSE_TTL_SEC, true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Course> listCourses(Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : size;
        // D-216 (audit P1-4): the key used to be a constant "all" — every
        // page/size combination shared one entry, so page 2 served page 1's
        // cached result and any evict polluted all paginations.
        String listKey = "p" + p + ":" + s;
        return cacheService.getOrLoad(CACHE_COURSE_LIST, currentTenant(), listKey,
                () -> {
                    Page<Course> pageResult = courseRepository.findAllByOrderByCourseIdAsc(PageRequest.of(p - 1, s));
                    return new ArrayList<>(pageResult.getContent());
                }, COURSE_LIST_TTL_SEC, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Course> searchCourses(String keyword) {
        requireNonBlank(keyword, "keyword");
        // 补齐高频未缓存读：search 结果走 30s 短 TTL 缓存（同样带租户维度）。
        return cacheService.getOrLoad(CACHE_COURSE_SEARCH, currentTenant(), keyword,
                () -> courseRepository.findByTitleContainingOrderByCourseIdAsc(keyword),
                COURSE_SEARCH_TTL_SEC, false);
    }

    /** Current tenant for cache-key scoping (defaults to "default" when unset). */
    private static String currentTenant() {
        return TenantContext.getTenantIdOrDefault();
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
        // D-182: evict cached course + list after a state change (P0: tenant-scoped key).
        cacheService.evict(CACHE_COURSE, currentTenant(), String.valueOf(courseId));
        cacheService.evictAll(CACHE_COURSE_LIST); // D-216: pagination keys are unbounded — evict whole cache
        cacheService.evictAll(CACHE_COURSE_SEARCH);
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
        // D-182: evict cached course + list after a state change (P0: tenant-scoped key).
        cacheService.evict(CACHE_COURSE, currentTenant(), String.valueOf(courseId));
        cacheService.evictAll(CACHE_COURSE_LIST); // D-216: pagination keys are unbounded — evict whole cache
        cacheService.evictAll(CACHE_COURSE_SEARCH);
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
