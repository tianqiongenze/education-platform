package com.ep.grading.api.grading;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Result of {@link AiGradingRpcService#grade(AiGradingRequest)}.
 *
 * <p>ai-platform integration (TODO sp04 closure): returned by the sp04 AI grading
 * Dubbo provider. sp05 maps the score/feedback into its
 * {@link GradingResult} with {@code graderType=AI}.
 *
 * <p>Iron Law 7 (GraalVM AOT): record + {@link Serializable}; uses
 * {@link LocalDateTime} (natively AOT-friendly, no timezone DB lookup on the
 * hot path) to mirror sp04's Python datetime surface.
 *
 * @param score      numeric score (0..maxScore from the request), never null
 * @param feedback   human/AI-readable feedback; never null, may be empty
 * @param confidence model confidence in [0.0, 1.0]; 0.0 when unknown
 * @param gradedAt   timestamp the AI grading completed, never null
 */
public record AiGradingResult(
        Double score,
        String feedback,
        Double confidence,
        LocalDateTime gradedAt
) implements Serializable {
}
