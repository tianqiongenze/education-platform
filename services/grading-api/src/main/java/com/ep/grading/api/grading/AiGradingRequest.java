package com.ep.grading.api.grading;

import java.io.Serializable;

/**
 * Request payload for {@link AiGradingRpcService#grade(AiGradingRequest)}.
 *
 * <p>ai-platform integration (TODO sp04 closure): the sp05 grading pipeline builds
 * this record for subjective questions and invokes the sp04 AI grading Dubbo
 * provider. sp04 runs the RAG + LLM rubric scoring and returns an
 * {@link AiGradingResult}.
 *
 * <p>Iron Law 7 (GraalVM AOT): record + {@link Serializable}, no reflection,
 * no runtime resource lookups. Field names match the sp04 AI grading agent
 * contract (see {@code services/ai-platform/ai_agent/graphs/grading_agent.py}).
 *
 * @param submissionId     logical submission id (ep_submission.id), never null
 * @param questionContent  full question text/prompt being graded, never null
 * @param studentAnswer    student free-form answer text, may be blank
 * @param maxScore         maximum score the rubric allows (e.g. 100)
 * @param rubric           rubric definition (id or inline JSON/YAML); never null
 * @param tenantId         multi-tenant isolation key (Iron Law / D-044 SaaS)
 */
public record AiGradingRequest(
        Long submissionId,
        String questionContent,
        String studentAnswer,
        Integer maxScore,
        String rubric,
        String tenantId
) implements Serializable {
}
