package com.ep.grading.grading.dto;

import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

/**
 * D-185: when {@code submissionCode} is non-null the grading pipeline runs the
 * source through the sandbox executor in the {@code auto_check} stage instead
 * of unconditionally returning PASSED. Callers that do not carry source code
 * (legacy paths) leave it null to preserve the old dev-mode fallback.
 */
public record StartGradingCmd(
        @NotNull Long submissionId,
        @NotNull Long gradedBy,
        String idempotencyKey,
        String submissionCode,
        String language
) implements Serializable {

    /** Backwards-compatible constructor (no source code — dev-mode fallback). */
    public StartGradingCmd(Long submissionId, Long gradedBy, String idempotencyKey) {
        this(submissionId, gradedBy, idempotencyKey, null, null);
    }
}
