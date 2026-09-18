package com.ep.grading.homework.impl;

import com.ep.cache.CacheService;
import com.ep.grading.common.SnowflakeIdGenerator;
import com.ep.grading.config.IdempotencyService;
import com.ep.grading.homework.AssignmentRepository;
import com.ep.grading.homework.dto.AssignmentCommand;
import com.ep.grading.homework.entity.Assignment;
import com.ep.grading.homework.entity.AssignmentStatus;
import com.ep.grading.homework.service.AssignmentService;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * D-048 P0-4: ConcurrentHashMap store replaced by Spring Data JPA {@link AssignmentRepository}.
 * D-048 P0-7: {@link SentinelResource} on {@link #create} for rate limiting (configurable
 * via Sentinel dashboard / Nacos datasource). Service interface unchanged.
 * D-182: L1+L2 cache via ep-cache SDK. Assignment template cached 600s (cacheEmpty=true);
 * reads wrapped with {@code getOrLoad}, writes evict.
 */
@DubboService
public class AssignmentServiceImpl implements AssignmentService {

    private static final String CACHE_ASSIGNMENT = "assignment";
    private static final String CACHE_ASSIGNMENT_BY_COURSE = "assignment:by-course";
    private static final long ASSIGNMENT_TTL_SEC = 600;

    private final AssignmentRepository assignmentRepository;
    private final CacheService cacheService;

    public AssignmentServiceImpl(AssignmentRepository assignmentRepository,
                                 CacheService cacheService) {
        this.assignmentRepository = assignmentRepository;
        this.cacheService = cacheService;
    }

    @Override
    @SentinelResource(value = "AssignmentService.create",
            blockHandler = "createBlockHandler", fallback = "createFallback")
    @Transactional
    public Assignment create(AssignmentCommand cmd) {
        Objects.requireNonNull(cmd, "AssignmentCommand cannot be null");
        if (cmd.title() == null || cmd.title().isBlank()) {
            throw new IllegalArgumentException("Assignment title cannot be blank");
        }
        Integer maxScore = cmd.maxScore();
        if (maxScore == null) {
            maxScore = 100;
        }
        if (maxScore < 1 || maxScore > 1000) {
            throw new IllegalArgumentException("maxScore must be between 1 and 1000");
        }

        LocalDateTime now = LocalDateTime.now();
        Assignment assignment = new Assignment(
                SnowflakeIdGenerator.getInstance().nextId(),
                cmd.title(),
                cmd.description(),
                cmd.courseId(),
                cmd.teacherId(),
                maxScore,
                cmd.deadline(),
                AssignmentStatus.DRAFT,
                now,
                now
        );
        Assignment saved = assignmentRepository.save(assignment);
        cacheService.evict(CACHE_ASSIGNMENT_BY_COURSE, String.valueOf(cmd.courseId()));
        return saved;
    }

    @Override
    @Transactional
    public Assignment publish(Long id) {
        Assignment assignment = requireById(id);
        // D-201: delegate to the aggregate's state machine instead of setStatus().
        assignment.publish();
        Assignment saved = assignmentRepository.save(assignment);
        cacheService.evict(CACHE_ASSIGNMENT, String.valueOf(id));
        cacheService.evict(CACHE_ASSIGNMENT_BY_COURSE, String.valueOf(assignment.getCourseId()));
        return saved;
    }

    @Override
    @Transactional
    public Assignment close(Long id) {
        Assignment assignment = requireById(id);
        // D-201: delegate to the aggregate's state machine instead of setStatus().
        assignment.close();
        Assignment saved = assignmentRepository.save(assignment);
        cacheService.evict(CACHE_ASSIGNMENT, String.valueOf(id));
        cacheService.evict(CACHE_ASSIGNMENT_BY_COURSE, String.valueOf(assignment.getCourseId()));
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Assignment findById(Long id) {
        if (id == null) {
            return null;
        }
        return cacheService.getOrLoad(CACHE_ASSIGNMENT, String.valueOf(id),
                () -> assignmentRepository.findById(id).orElse(null),
                ASSIGNMENT_TTL_SEC, true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Assignment> findByCourseId(Long courseId) {
        if (courseId == null) {
            return List.of();
        }
        return cacheService.getOrLoad(CACHE_ASSIGNMENT_BY_COURSE, String.valueOf(courseId),
                () -> assignmentRepository.findByCourseId(courseId),
                ASSIGNMENT_TTL_SEC, false);
    }

    private Assignment requireById(Long id) {
        return assignmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found: " + id));
    }

    /** Sentinel block handler — must match method signature plus BlockException. */
    public Assignment createBlockHandler(AssignmentCommand cmd, com.alibaba.csp.sentinel.slots.block.BlockException ex) {
        throw new IllegalStateException("Assignment creation rate limited: " + ex.getClass().getSimpleName(), ex);
    }

    /** Sentinel fallback — invoked when the resource or downstream throws. */
    public Assignment createFallback(AssignmentCommand cmd, Throwable t) {
        throw new IllegalStateException("Assignment creation failed: " + t.getMessage(), t);
    }
}
