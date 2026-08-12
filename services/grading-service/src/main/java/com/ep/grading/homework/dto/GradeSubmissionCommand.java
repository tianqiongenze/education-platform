package com.ep.grading.homework.dto;

import java.io.Serializable;

public record GradeSubmissionCommand(
        Long submissionId,
        Integer score,
        String feedback,
        Long graderId,
        String idempotencyKey
) implements Serializable {
}
