package com.ep.grading.api.grading;

import java.io.Serializable;
import java.time.Instant;

/**
 * Result of {@link GradingRpcService#gradeSubmission(GradingRequest)}.
 *
 * <p>D-050 P2-5: returned by the grading-service grading Dubbo provider. The MCP
 * {@code submission.grade} tool in mcp-server maps this record into its
 * tool response map (status / score / feedback / gradedAt / graderType).
 *
 * <p>Iron Law 7 (GraalVM AOT): record + {@link Serializable}; {@link Instant}
 * is natively AOT-friendly (no reflection, no timezone DB lookup at runtime
 * on the hot path).
 *
 * @param score       numeric score (0-100 unless a custom rubric scale is used)
 * @param feedback    human/AI-readable feedback; never null, may be empty
 * @param gradedAt    instant the grading completed, never null
 * @param graderType  {@code AI} (AI-assisted subjective) or {@code MANUAL}
 * @param tenantId    tenant id echoed back from the request for traceability
 */
public record GradingResult(
        Integer score,
        String feedback,
        Instant gradedAt,
        GraderType graderType,
        String tenantId
) implements Serializable {
}
