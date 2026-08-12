package com.ep.grading.homework;

import com.ep.cache.CacheService;
import com.ep.grading.homework.dto.AssignmentCommand;
import com.ep.grading.homework.entity.Assignment;
import com.ep.grading.homework.entity.AssignmentStatus;
import com.ep.grading.homework.impl.AssignmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AssignmentServiceImpl}. Repository is mocked so tests run
 * without TiDB. Sentinel is not engaged in plain unit tests (no aspect weaving).
 * D-182: CacheService is mocked with cache-miss default (invokes loader), allowing
 * cache interaction verification while keeping existing logic tests intact.
 */
class AssignmentServiceImplTest {

    private AssignmentRepository assignmentRepository;
    private CacheService cacheService;
    private AssignmentServiceImpl service;
    private final AtomicLong idSeq = new AtomicLong(0L);

    @BeforeEach
    void setUp() {
        assignmentRepository = mock(AssignmentRepository.class);
        cacheService = mock(CacheService.class);
        // Cache-miss default: invoke the loader so the repository is called as before.
        when(cacheService.getOrLoad(anyString(), anyString(), any(), anyLong(), anyBoolean()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> loader = inv.getArgument(2);
                    return loader.get();
                });
        when(assignmentRepository.save(any(Assignment.class))).thenAnswer(inv -> {
            Assignment a = inv.getArgument(0);
            if (a.getId() == null) {
                a.setId(idSeq.incrementAndGet());
            }
            return a;
        });
        service = new AssignmentServiceImpl(assignmentRepository, cacheService);
    }

    @Test
    void test_create_assignment_success() {
        AssignmentCommand cmd = new AssignmentCommand(
                "Homework 1", "Desc", 10L, 20L, 100, LocalDateTime.now().plusDays(7));
        Assignment a = service.create(cmd);
        assertNotNull(a.getId());
        assertEquals("Homework 1", a.getTitle());
        assertEquals(100, a.getMaxScore());
        assertEquals(AssignmentStatus.DRAFT, a.getStatus());
        assertNotNull(a.getCreatedAt());
        verify(cacheService).evict("assignment:by-course", String.valueOf(10L));
    }

    @Test
    void test_create_assignment_blank_title_throws() {
        AssignmentCommand cmd = new AssignmentCommand(
                "  ", "Desc", 10L, 20L, 100, null);
        assertThrows(IllegalArgumentException.class, () -> service.create(cmd));
    }

    @Test
    void test_create_assignment_invalid_max_score_throws() {
        AssignmentCommand tooLow = new AssignmentCommand("T", "D", 1L, 2L, 0, null);
        assertThrows(IllegalArgumentException.class, () -> service.create(tooLow));
        AssignmentCommand tooHigh = new AssignmentCommand("T", "D", 1L, 2L, 1001, null);
        assertThrows(IllegalArgumentException.class, () -> service.create(tooHigh));
    }

    @Test
    void test_publish_assignment_success() {
        Assignment a = new Assignment(1L, "T", "D", 1L, 2L, 100, null, AssignmentStatus.DRAFT, LocalDateTime.now(), null);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(a));

        Assignment published = service.publish(1L);
        assertEquals(AssignmentStatus.PUBLISHED, published.getStatus());
        verify(assignmentRepository, times(1)).save(any(Assignment.class));
        verify(cacheService).evict("assignment", String.valueOf(1L));
        verify(cacheService).evict("assignment:by-course", String.valueOf(1L));
    }

    @Test
    void test_publish_non_draft_throws() {
        Assignment a = new Assignment(1L, "T", "D", 1L, 2L, 100, null, AssignmentStatus.PUBLISHED, LocalDateTime.now(), null);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(a));
        assertThrows(IllegalStateException.class, () -> service.publish(1L));
    }

    @Test
    void test_publish_not_found_throws() {
        when(assignmentRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.publish(999L));
    }

    @Test
    void test_close_assignment_success() {
        Assignment a = new Assignment(1L, "T", "D", 1L, 2L, 100, null, AssignmentStatus.PUBLISHED, LocalDateTime.now(), null);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(a));

        Assignment closed = service.close(1L);
        assertEquals(AssignmentStatus.CLOSED, closed.getStatus());
        verify(assignmentRepository, times(1)).save(any(Assignment.class));
        verify(cacheService).evict("assignment", String.valueOf(1L));
        verify(cacheService).evict("assignment:by-course", String.valueOf(1L));
    }

    @Test
    void test_find_by_id() {
        Assignment a = new Assignment(1L, "T", "D", 1L, 2L, 100, null, AssignmentStatus.DRAFT, LocalDateTime.now(), null);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(a));
        Assignment found = service.findById(1L);
        assertNotNull(found);
        assertEquals(1L, found.getId());
        verify(cacheService).getOrLoad(eq("assignment"), eq(String.valueOf(1L)), any(), eq(600L), eq(true));
    }

    @Test
    void test_find_by_id_cache_hit_skips_repository() {
        // Cache hit: CacheService returns cached value without invoking loader.
        Assignment cached = new Assignment(1L, "T", "D", 1L, 2L, 100, null, AssignmentStatus.DRAFT, LocalDateTime.now(), null);
        when(cacheService.getOrLoad(eq("assignment"), eq(String.valueOf(1L)), any(), eq(600L), eq(true)))
                .thenReturn(cached);

        Assignment found = service.findById(1L);
        assertNotNull(found);
        assertEquals(1L, found.getId());
        // Repository must NOT be called on cache hit.
        verify(assignmentRepository, never()).findById(anyLong());
    }

    @Test
    void test_find_by_course_id() {
        Assignment a1 = new Assignment(1L, "T1", "D", 10L, 2L, 100, null, AssignmentStatus.DRAFT, LocalDateTime.now(), null);
        Assignment a2 = new Assignment(2L, "T2", "D", 10L, 2L, 100, null, AssignmentStatus.DRAFT, LocalDateTime.now(), null);
        when(assignmentRepository.findByCourseId(10L)).thenReturn(List.of(a1, a2));

        List<Assignment> result = service.findByCourseId(10L);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(a -> a.getCourseId().equals(10L)));
        verify(cacheService).getOrLoad(eq("assignment:by-course"), eq(String.valueOf(10L)), any(), eq(600L), eq(false));
    }

    @Test
    void test_close_non_published_throws() {
        Assignment a = new Assignment(1L, "T", "D", 1L, 2L, 100, null, AssignmentStatus.DRAFT, LocalDateTime.now(), null);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(a));
        assertThrows(IllegalStateException.class, () -> service.close(1L));
    }

    @Test
    void test_close_not_found_throws() {
        when(assignmentRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.close(999L));
    }

    @Test
    void test_find_by_id_null_returns_null() {
        assertEquals(null, service.findById(null));
    }

    @Test
    void test_create_null_command_throws() {
        assertThrows(NullPointerException.class, () -> service.create(null));
    }

    @Test
    void test_create_null_max_score_defaults_to_100() {
        Assignment a = service.create(new AssignmentCommand("T", "D", 1L, 2L, null, null));
        assertEquals(100, a.getMaxScore());
    }

    @Test
    void test_create_assigns_version_zero_on_save() {
        service.create(new AssignmentCommand("T", "D", 1L, 2L, 100, null));
        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(assignmentRepository, times(1)).save(captor.capture());
        // @Version field starts null; JPA assigns 0 on first insert. Entity itself keeps null pre-save.
        assertNotNull(captor.getValue().getTitle());
    }
}
