package com.ep.grading.homework;

import com.ep.grading.config.IdempotencyService;
import com.ep.grading.homework.dto.GradeSubmissionCommand;
import com.ep.grading.homework.dto.SubmissionCommand;
import com.ep.grading.homework.entity.Submission;
import com.ep.grading.homework.entity.SubmissionStatus;
import com.ep.grading.homework.impl.SubmissionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SubmissionServiceImpl}. Repository and idempotency are mocked so
 * tests run without TiDB / Redis (those are exercised by @Tag("integration") tests).
 */
class SubmissionServiceImplTest {

    private SubmissionRepository submissionRepository;
    private IdempotencyService idempotencyService;
    private SubmissionServiceImpl service;
    private final AtomicLong idSeq = new AtomicLong(0L);

    @BeforeEach
    void setUp() {
        submissionRepository = mock(SubmissionRepository.class);
        idempotencyService = mock(IdempotencyService.class);
        // Default: no prior claim in Redis -> service proceeds to create.
        when(idempotencyService.claimOrFetch(anyString(), any())).thenReturn(null);
        // Default save echoes the entity back with an assigned id.
        when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(idSeq.incrementAndGet());
            }
            return s;
        });
        service = new SubmissionServiceImpl(submissionRepository, idempotencyService);
    }

    @Test
    void test_submit_success() {
        when(submissionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        Submission s = service.submit(new SubmissionCommand(1L, 10L, "content", "key-1"));
        assertNotNull(s.getId());
        assertEquals(1L, s.getAssignmentId());
        assertEquals(10L, s.getStudentId());
        assertEquals(SubmissionStatus.SUBMITTED, s.getStatus());
        // @Version is null pre-persist; JPA assigns 0 on first insert.
        assertNotNull(s.getSubmittedAt());
        verify(submissionRepository, times(1)).save(any(Submission.class));
    }

    @Test
    void test_submit_idempotent_same_key_returns_same() {
        // First call: no existing row in DB; service saves new entity with id=1.
        Submission first = new Submission();
        first.setId(1L);
        first.setAssignmentId(1L);
        first.setStudentId(10L);
        first.setContent("content-a");
        first.setIdempotencyKey("idem-key");
        when(submissionRepository.findByIdempotencyKey("idem-key"))
                .thenReturn(Optional.empty())   // first probe
                .thenReturn(Optional.of(first)); // second probe (parallel request already persisted)
        when(submissionRepository.save(any(Submission.class))).thenReturn(first);

        Submission a = service.submit(new SubmissionCommand(1L, 10L, "content-a", "idem-key"));
        assertEquals(1L, a.getId());
        // Second call: Redis returns null, DB probe returns the persisted row.
        Submission b = service.submit(new SubmissionCommand(1L, 10L, "content-b", "idem-key"));
        assertEquals(1L, b.getId());
        assertEquals("content-a", b.getContent());
        // Second submit must not call save again.
        verify(submissionRepository, times(1)).save(any(Submission.class));
    }

    @Test
    void test_submit_idempotent_redis_hit_returns_cached() {
        // Redis already has the key -> service returns cached row without saving.
        when(idempotencyService.claimOrFetch("redis-hit", null)).thenReturn("7");
        Submission cached = new Submission();
        cached.setId(7L);
        when(submissionRepository.findById(7L)).thenReturn(Optional.of(cached));

        Submission result = service.submit(new SubmissionCommand(1L, 10L, "c", "redis-hit"));
        assertEquals(7L, result.getId());
        verify(submissionRepository, never()).save(any());
    }

    @Test
    void test_grade_success() {
        Submission s = new Submission();
        s.setId(5L);
        s.setAssignmentId(1L);
        s.setStudentId(10L);
        s.setContent("content");
        s.setStatus(SubmissionStatus.SUBMITTED);
        s.setVersion(0);
        when(submissionRepository.findById(5L)).thenReturn(Optional.of(s));
        when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> inv.getArgument(0));

        Submission graded = service.grade(new GradeSubmissionCommand(5L, 90, "Good", 5L, "g-key"));
        assertEquals(90, graded.getScore());
        assertEquals("Good", graded.getFeedback());
        assertEquals(SubmissionStatus.GRADED, graded.getStatus());
        assertNotNull(graded.getGradedAt());
        verify(submissionRepository, times(1)).save(any(Submission.class));
    }

    @Test
    void test_grade_idempotent_db_hit_returns_existing() {
        Submission existing = new Submission();
        existing.setId(9L);
        existing.setScore(80);
        existing.setFeedback("ok");
        when(submissionRepository.findByIdempotencyKey("g-key-2")).thenReturn(Optional.of(existing));

        Submission first = service.grade(new GradeSubmissionCommand(5L, 80, "ok", 5L, "g-key-2"));
        assertEquals(9L, first.getId());
        Submission second = service.grade(new GradeSubmissionCommand(5L, 99, "diff", 5L, "g-key-2"));
        assertEquals(9L, second.getId());
        assertEquals(80, second.getScore());
        assertEquals("ok", second.getFeedback());
        verify(submissionRepository, never()).save(any());
    }

    @Test
    void test_grade_optimistic_lock_version_mismatch() {
        Submission s = new Submission();
        s.setId(5L);
        s.setVersion(0);
        when(submissionRepository.findById(5L)).thenReturn(Optional.of(s));
        // Simulate JPA @Version conflict on save.
        doThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                Submission.class.getName(), 5L, null))
                .when(submissionRepository).save(any(Submission.class));

        assertThrows(IllegalStateException.class,
                () -> service.grade(new GradeSubmissionCommand(5L, 70, "again", 5L, "g-key-3")));
    }

    @Test
    void test_find_by_id() {
        Submission s = new Submission();
        s.setId(1L);
        when(submissionRepository.findById(1L)).thenReturn(Optional.of(s));
        Submission found = service.findById(1L);
        assertNotNull(found);
        assertEquals(1L, found.getId());
    }

    @Test
    void test_find_by_assignment_id() {
        Submission s1 = new Submission();
        s1.setId(1L);
        s1.setAssignmentId(1L);
        Submission s2 = new Submission();
        s2.setId(2L);
        s2.setAssignmentId(1L);
        when(submissionRepository.findByAssignmentId(1L)).thenReturn(List.of(s1, s2));

        List<Submission> result = service.findByAssignmentId(1L);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(s -> s.getAssignmentId().equals(1L)));
    }

    @Test
    void test_grade_not_found_throws() {
        when(submissionRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.grade(new GradeSubmissionCommand(999L, 10, "f", 1L, "no-such")));
    }

    @Test
    void test_find_by_id_null_returns_null() {
        assertNull(service.findById(null));
    }

    @Test
    void test_find_by_assignment_id_null_returns_empty() {
        assertTrue(service.findByAssignmentId(null).isEmpty());
    }

    @Test
    void test_submit_without_idempotency_key_creates_distinct() {
        when(submissionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        Submission a = service.submit(new SubmissionCommand(1L, 10L, "c", null));
        Submission b = service.submit(new SubmissionCommand(1L, 10L, "c", null));
        assertTrue(a.getId() != b.getId());
        // No idempotency key -> Redis claim must not be invoked with a key.
        verify(idempotencyService, never()).claimOrFetch(anyString(), any());
    }

    @Test
    void test_submit_null_command_throws() {
        assertThrows(NullPointerException.class, () -> service.submit(null));
    }

    @Test
    void test_grade_null_command_throws() {
        assertThrows(NullPointerException.class, () -> service.grade(null));
    }
}
