package com.ep.grading.api.grading;

import java.io.Serializable;

/**
 * Request payload for {@link GradingRpcService#gradeSubmission(GradingRequest)}.
 *
 * <p>D-050 P2-5: closes the grading-service grading Dubbo API contract gap surfaced by
 * audit D-048. Consumers (mcp-server, BFF) build this record and call
 * the Dubbo provider hosted by grading-service; the provider performs the
 * real grading pipeline (objective compare + subjective AI/manual stage).
 *
 * <p>Iron Law 7 (GraalVM AOT): record + {@link Serializable}, no reflection,
 * no runtime resource lookups. Field names match the JSON schema documented
 * in grading-service-sdd §grading so MCP tool {@code submission.grade} can map 1:1.
 *
 * @param submissionId logical submission id (ep_submission.id), never null
 * @param studentId    student id owning the submission
 * @param questionId   question id being graded (0/null if pipeline-level)
 * @param answer       student answer text (objective: exact-match expected;
 *                     subjective: free-form prose passed to AI/manual stage)
 * @param rubricId     rubric id used for subjective scoring; null/blank
 *                     means default rubric / objective-only grading
 * @param tenantId     multi-tenant isolation key (Iron Law / D-044 SaaS)
 */
public record GradingRequest(
        Long submissionId,
        Long studentId,
        Long questionId,
        String answer,
        String rubricId,
        String tenantId
) implements Serializable {
}
