package com.ep.grading.homework;

import com.ep.grading.homework.entity.PlagiarismCheck;
import com.ep.grading.homework.entity.PlagiarismStatus;
import com.ep.grading.homework.impl.PlagiarismServiceImpl;
import com.ep.grading.homework.entity.Submission;
import com.ep.grading.homework.service.SubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlagiarismServiceImpl}. Repository + SubmissionService mocked.
 */
class PlagiarismServiceImplTest {

    private PlagiarismCheckRepository plagiarismCheckRepository;
    private SubmissionService submissionService;
    private PlagiarismServiceImpl service;
    private final AtomicLong idSeq = new AtomicLong(0L);

    @BeforeEach
    void setUp() throws Exception {
        plagiarismCheckRepository = mock(PlagiarismCheckRepository.class);
        submissionService = mock(SubmissionService.class);
        when(plagiarismCheckRepository.save(any(PlagiarismCheck.class))).thenAnswer(inv -> {
            PlagiarismCheck c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(idSeq.incrementAndGet());
            }
            return c;
        });
        service = new PlagiarismServiceImpl(plagiarismCheckRepository);
        java.lang.reflect.Field f = PlagiarismServiceImpl.class.getDeclaredField("submissionService");
        f.setAccessible(true);
        f.set(service, submissionService);
    }

    @Test
    void test_check_clean() {
        // submissionId=1 -> pseudo length 17 -> similarity 0.17 -> CLEAN
        PlagiarismCheck check = service.check(1L);
        assertNotNull(check.getId());
        assertEquals(1L, check.getSubmissionId());
        assertTrue(check.getSimilarityScore() < 0.3,
                "expected similarity < 0.3 but got " + check.getSimilarityScore());
        assertEquals(PlagiarismStatus.CLEAN, check.getStatus());
        assertNotNull(check.getCheckedAt());
    }

    @Test
    void test_check_flagged() {
        // submissionId=10 -> pseudo length 170 -> 170%100=70 -> similarity 0.7 -> FLAGGED
        PlagiarismCheck check = service.check(10L);
        assertTrue(check.getSimilarityScore() >= 0.7,
                "expected similarity >= 0.7 but got " + check.getSimilarityScore());
        assertEquals(PlagiarismStatus.FLAGGED, check.getStatus());
    }

    @Test
    void test_find_by_submission_id() {
        PlagiarismCheck created = new PlagiarismCheck();
        created.setId(1L);
        created.setSubmissionId(1L);
        when(plagiarismCheckRepository.findBySubmissionId(1L)).thenReturn(Optional.of(created));
        PlagiarismCheck found = service.findBySubmissionId(1L);
        assertNotNull(found);
        assertEquals(1L, found.getId());
    }

    @Test
    void test_check_null_submission_id_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.check(null));
    }

    @Test
    void test_find_by_submission_id_null_returns_null() {
        assertEquals(null, service.findBySubmissionId(null));
    }

    @Test
    void test_find_by_submission_id_not_found_returns_null() {
        when(plagiarismCheckRepository.findBySubmissionId(999L)).thenReturn(Optional.empty());
        assertEquals(null, service.findBySubmissionId(999L));
    }

    @Test
    void test_check_reviewing_status() {
        // submissionId=2 -> pseudo length 34 -> 0.34 -> REVIEWING
        PlagiarismCheck reviewing = service.check(2L);
        assertEquals(PlagiarismStatus.REVIEWING, reviewing.getStatus(),
                "expected REVIEWING but got " + reviewing.getSimilarityScore());
    }

    @Test
    void test_check_uses_submission_content_when_available() {
        Submission s = new Submission();
        s.setContent("a".repeat(80));  // length 80 -> 80%100=80 -> 0.80 -> FLAGGED
        when(submissionService.findById(99L)).thenReturn(s);

        PlagiarismCheck check = service.check(99L);
        assertEquals(PlagiarismStatus.FLAGGED, check.getStatus());
        assertEquals(0.8, check.getSimilarityScore(), 0.0001);
        verify(plagiarismCheckRepository, times(1)).save(any(PlagiarismCheck.class));
    }
}
