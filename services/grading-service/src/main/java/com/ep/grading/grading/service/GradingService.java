package com.ep.grading.grading.service;

import com.ep.grading.grading.dto.StartGradingCmd;
import com.ep.grading.grading.entity.GradePipeline;
import com.ep.grading.grading.entity.GradeStage;

import java.util.List;

public interface GradingService {
    GradePipeline startGrading(StartGradingCmd cmd);
    GradePipeline findById(Long id);
    GradePipeline findBySubmissionId(Long submissionId);
    List<GradeStage> getStages(Long pipelineId);
}
