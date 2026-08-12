package com.ep.grading.homework.service;

import com.ep.grading.homework.dto.AssignmentCommand;
import com.ep.grading.homework.entity.Assignment;

import java.util.List;

public interface AssignmentService {
    Assignment create(AssignmentCommand cmd);
    Assignment publish(Long id);
    Assignment close(Long id);
    Assignment findById(Long id);
    List<Assignment> findByCourseId(Long courseId);
}
