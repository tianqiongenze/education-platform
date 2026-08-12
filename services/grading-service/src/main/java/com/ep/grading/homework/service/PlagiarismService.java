package com.ep.grading.homework.service;

import com.ep.grading.homework.entity.PlagiarismCheck;

public interface PlagiarismService {
    PlagiarismCheck check(Long submissionId);
    PlagiarismCheck findBySubmissionId(Long submissionId);
}
