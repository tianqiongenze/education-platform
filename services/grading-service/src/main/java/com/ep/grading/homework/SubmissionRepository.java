package com.ep.grading.homework;

import com.ep.grading.homework.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Optional;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    Optional<Submission> findByIdempotencyKey(String idempotencyKey);

    List<Submission> findByAssignmentId(Long assignmentId);

    @NonNull
    @Override
    List<Submission> findAll();
}
