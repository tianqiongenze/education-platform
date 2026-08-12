package com.ep.grading.homework;

import com.ep.grading.homework.entity.PlagiarismCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlagiarismCheckRepository extends JpaRepository<PlagiarismCheck, Long> {

    Optional<PlagiarismCheck> findBySubmissionId(Long submissionId);
}
