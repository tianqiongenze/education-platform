package com.ep.grading.grading;

import com.ep.grading.grading.entity.GradeStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GradeStageRepository extends JpaRepository<GradeStage, Long> {

    List<GradeStage> findByPipelineIdOrderByPipelineId(Long pipelineId);

    List<GradeStage> findByPipelineId(Long pipelineId);
}
