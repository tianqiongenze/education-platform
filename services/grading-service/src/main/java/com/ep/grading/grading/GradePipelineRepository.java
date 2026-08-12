package com.ep.grading.grading;

import com.ep.grading.grading.entity.GradePipeline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GradePipelineRepository extends JpaRepository<GradePipeline, Long> {

    Optional<GradePipeline> findByIdempotencyKey(String idempotencyKey);

    Optional<GradePipeline> findBySubmissionId(Long submissionId);
}
