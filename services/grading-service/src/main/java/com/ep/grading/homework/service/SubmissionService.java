package com.ep.grading.homework.service;

import com.ep.grading.homework.dto.GradeSubmissionCommand;
import com.ep.grading.homework.dto.SubmissionCommand;
import com.ep.grading.homework.entity.Submission;

import java.util.List;

public interface SubmissionService {
    Submission submit(SubmissionCommand cmd);
    Submission grade(GradeSubmissionCommand cmd);
    Submission findById(Long id);
    List<Submission> findByAssignmentId(Long assignmentId);
}
