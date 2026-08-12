package com.ep.grading.homework.dto;

import java.io.Serializable;

public record SubmissionCommand(
        Long assignmentId,
        Long studentId,
        String content,
        String idempotencyKey
) implements Serializable {
}
