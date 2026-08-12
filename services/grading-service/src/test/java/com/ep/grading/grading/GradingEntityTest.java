package com.ep.grading.grading;

import com.ep.grading.grading.entity.GradePipeline;
import com.ep.grading.grading.entity.GradePipelineStatus;
import com.ep.grading.grading.entity.GradeStage;
import com.ep.grading.grading.entity.GradeStageStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GradingEntityTest {

    @Test
    void grade_pipeline_getters_setters() {
        GradePipeline p = new GradePipeline();
        p.setId(1L);
        p.setSubmissionId(2L);
        p.setStatus(GradePipelineStatus.RUNNING);
        p.setStages(new ArrayList<>());
        p.setTotalScore(80);
        p.setGradedBy(5L);
        p.setIdempotencyKey("k");
        assertEquals(1L, p.getId());
        assertEquals(2L, p.getSubmissionId());
        assertEquals(GradePipelineStatus.RUNNING, p.getStatus());
        assertEquals(80, p.getTotalScore());
        assertEquals(5L, p.getGradedBy());
        assertEquals("k", p.getIdempotencyKey());
        assertNotNull(p.getStages());
    }

    @Test
    void grade_stage_getters_setters() {
        GradeStage s = new GradeStage();
        s.setId(1L);
        s.setStageName("auto_check");
        s.setStatus(GradeStageStatus.PASSED);
        s.setStageScore(40);
        s.setStageResult("ok");
        s.setErrorMessage(null);
        assertEquals(1L, s.getId());
        assertEquals("auto_check", s.getStageName());
        assertEquals(GradeStageStatus.PASSED, s.getStatus());
        assertEquals(40, s.getStageScore());
        assertEquals("ok", s.getStageResult());
    }

    @Test
    void grade_pipeline_status_values() {
        assertEquals(5, GradePipelineStatus.values().length);
        assertEquals(GradePipelineStatus.COMPLETED, GradePipelineStatus.valueOf("COMPLETED"));
    }

    @Test
    void grade_stage_status_values() {
        assertEquals(5, GradeStageStatus.values().length);
        assertEquals(GradeStageStatus.PENDING, GradeStageStatus.valueOf("PENDING"));
    }
}
