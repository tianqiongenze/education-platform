package com.ep.grading.grading.dto;

import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

public record StartGradingCmd(
        @NotNull Long submissionId,
        @NotNull Long gradedBy,
        String idempotencyKey
) implements Serializable {
}
