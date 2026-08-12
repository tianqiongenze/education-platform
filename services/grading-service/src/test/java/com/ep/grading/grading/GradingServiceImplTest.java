package com.ep.grading.grading;

import com.ep.grading.config.IdempotencyService;
import com.ep.grading.grading.dto.StartGradingCmd;
import com.ep.grading.grading.entity.GradePipeline;
import com.ep.grading.grading.entity.GradePipelineStatus;
import com.ep.grading.grading.entity.GradeStage;
import com.ep.grading.grading.entity.GradeStageStatus;
import com.ep.grading.grading.impl.GradingServiceImpl;
import com.ep.grading.homework.entity.PlagiarismCheck;
import com.ep.grading.homework.entity.PlagiarismStatus;
import com.ep.grading.homework.service.PlagiarismService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GradingServiceImpl}. Repository + idempotency + PlagiarismService
 * are mocked so tests run without TiDB / Redis / Dubbo. The DubboReference field is set
 * via reflection in {@link #setUp()} (test-only; production wiring is by Dubbo).
 */
class GradingServiceImplTest {

    private GradePipelineRepository pipelineRepository;
    private GradeStageRepository stageRepository;
    private IdempotencyService idempotencyService;
    private PlagiarismService plagiarismService;
    private GradingServiceImpl service;
    private final AtomicLong idSeq = new AtomicLong(0L);

    @BeforeEach
    void setUp() throws Exception {
        pipelineRepository = mock(GradePipelineRepository.class);
        stageRepository = mock(GradeStageRepository.class);
        idempotencyService = mock(IdempotencyService.class);
        plagiarismService = mock(PlagiarismService.class);

        when(idempotencyService.claimOrFetch(anyString(), any())).thenReturn(null);
        when(pipelineRepository.save(any(GradePipeline.class))).thenAnswer(inv -> {
            GradePipeline p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(idSeq.incrementAndGet());
            }
            return p;
        });
        PlagiarismCheck clean = new PlagiarismCheck();
        clean.setId(1L);
        clean.setSubmissionId(1L);
        clean.setSimilarityScore(0.1);
        clean.setStatus(PlagiarismStatus.CLEAN);
        clean.setMatchedSources("[]");
        when(plagiarismService.check(any())).thenReturn(clean);

        service = new GradingServiceImpl(pipelineRepository, stageRepository, idempotencyService);
        java.lang.reflect.Field f = GradingServiceImpl.class.getDeclaredField("plagiarismService");
        f.setAccessible(true);
        f.set(service, plagiarismService);
    }

    @Test
    void test_start_grading_success() {
        when(pipelineRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        GradePipeline pipeline = service.startGrading(new StartGradingCmd(1L, 5L, "grade-key-1"));
        assertNotNull(pipeline.getId());
        assertEquals(1L, pipeline.getSubmissionId());
        assertEquals(5L, pipeline.getGradedBy());
        assertNotNull(pipeline.getStartedAt());
    }

    @Test
    void test_start_grading_idempotent_db_hit() {
        GradePipeline existing = new GradePipeline();
        existing.setId(7L);
        existing.setSubmissionId(1L);
        when(pipelineRepository.findByIdempotencyKey("grade-key-2")).thenReturn(Optional.of(existing));

        GradePipeline first = service.startGrading(new StartGradingCmd(1L, 5L, "grade-key-2"));
        GradePipeline second = service.startGrading(new StartGradingCmd(2L, 6L, "grade-key-2"));
        assertEquals(7L, first.getId());
        assertEquals(7L, second.getId());
        assertEquals(1L, second.getSubmissionId());
        verify(pipelineRepository, never()).save(any());
    }

    @Test
    void test_start_grading_idempotent_redis_hit() {
        when(idempotencyService.claimOrFetch("redis-key", null)).thenReturn("3");
        GradePipeline cached = new GradePipeline();
        cached.setId(3L);
        cached.setSubmissionId(1L);
        when(pipelineRepository.findById(3L)).thenReturn(Optional.of(cached));

        GradePipeline result = service.startGrading(new StartGradingCmd(1L, 5L, "redis-key"));
        assertEquals(3L, result.getId());
        verify(pipelineRepository, never()).save(any());
    }

    @Test
    void test_find_by_id() {
        GradePipeline pipeline = new GradePipeline();
        pipeline.setId(1L);
        when(pipelineRepository.findById(1L)).thenReturn(Optional.of(pipeline));
        GradePipeline found = service.findById(1L);
        assertNotNull(found);
        assertEquals(1L, found.getId());
    }

    @Test
    void test_find_by_submission_id() {
        GradePipeline pipeline = new GradePipeline();
        pipeline.setId(1L);
        pipeline.setSubmissionId(42L);
        when(pipelineRepository.findBySubmissionId(42L)).thenReturn(Optional.of(pipeline));
        GradePipeline found = service.findBySubmissionId(42L);
        assertNotNull(found);
        assertEquals(42L, found.getSubmissionId());
    }

    @Test
    void test_get_stages_returns_three_stages() {
        when(pipelineRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        GradePipeline pipeline = service.startGrading(new StartGradingCmd(1L, 5L, "grade-key-5"));
        when(stageRepository.findByPipelineId(pipeline.getId())).thenReturn(new ArrayList<>(pipeline.getStages()));
        List<GradeStage> stages = service.getStages(pipeline.getId());
        assertEquals(3, stages.size());
        assertEquals("auto_check", stages.get(0).getStageName());
        assertEquals("plagiarism_check", stages.get(1).getStageName());
        assertEquals("manual_review", stages.get(2).getStageName());
    }

    @Test
    void test_pipeline_completes_after_auto_stages() {
        when(pipelineRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        GradePipeline pipeline = service.startGrading(new StartGradingCmd(1L, 5L, "grade-key-6"));
        List<GradeStage> stages = pipeline.getStages();
        assertEquals(GradeStageStatus.PASSED, stages.get(0).getStatus());
        assertEquals(GradeStageStatus.PASSED, stages.get(1).getStatus());
        assertEquals(GradeStageStatus.PENDING, stages.get(2).getStatus());
        assertEquals(GradePipelineStatus.COMPLETED, pipeline.getStatus());
        assertNotNull(pipeline.getCompletedAt());
        assertTrue(pipeline.getTotalScore() > 0);
    }

    @Test
    void test_find_by_id_null_returns_null() {
        assertNull(service.findById(null));
    }

    @Test
    void test_find_by_submission_id_null_returns_null() {
        assertNull(service.findBySubmissionId(null));
    }

    @Test
    void test_get_stages_unknown_pipeline_returns_empty() {
        when(stageRepository.findByPipelineId(999L)).thenReturn(List.of());
        List<GradeStage> stages = service.getStages(999L);
        assertTrue(stages.isEmpty());
    }

    @Test
    void test_start_grading_null_command_throws() {
        assertThrows(NullPointerException.class, () -> service.startGrading(null));
    }

    @Test
    void test_pipeline_cascade_persists_stages() {
        when(pipelineRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        GradePipeline pipeline = service.startGrading(new StartGradingCmd(1L, 5L, "grade-key-7"));
        ArgumentCaptor<GradePipeline> captor = ArgumentCaptor.forClass(GradePipeline.class);
        verify(pipelineRepository, times(1)).save(captor.capture());
        GradePipeline saved = captor.getValue();
        assertEquals(3, saved.getStages().size());
        assertTrue(saved.getStages().stream().allMatch(s -> s.getPipeline() == saved));
    }
}
