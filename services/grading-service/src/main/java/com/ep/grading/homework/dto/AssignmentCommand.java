package com.ep.grading.homework.dto;

import java.io.Serializable;
import java.time.LocalDateTime;

public record AssignmentCommand(
        String title,
        String description,
        Long courseId,
        Long teacherId,
        Integer maxScore,
        LocalDateTime deadline
) implements Serializable {
    public AssignmentCommand {
        if (maxScore == null) {
            maxScore = 100;
        }
    }
}
