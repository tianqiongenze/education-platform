package com.ep.grading.api.grading;

/**
 * ai-platform AI grading Dubbo RPC contract.
 *
 * <p>Closes the TODO(ai-grading) gap in {@code GradingRpcServiceImpl#gradeSubjective}:
 * subjective questions are graded by the AI gateway's RAG + LLM rubric scoring agent
 * (see {@code services/ai-platform/ai_agent/graphs/grading_agent.py}). grading-service
 * {@code @DubboReference}s this interface and delegates subjective grading
 * here when {@code ep.ai-grading.enabled=true}.
 *
 * <p>Iron Law 6: Dubbo 3.3.6 + Spring Cloud Alibaba 2025.1.0.0. No Netflix
 * components, no REST/OpenFeign fallback.
 * Iron Law 7: all parameters and return types are {@link java.io.Serializable}
 * records, GraalVM AOT-friendly.
 *
 * <p>Thread-safety: implementations must be stateless and safe for concurrent
 * invocation under Java 21 virtual threads.
 *
 * <p>Failure semantics: on grading failure the provider should throw a
 * {@link RuntimeException}; the grading-service consumer catches it and applies the
 * configured fallback ({@code pending} or {@code fail}) — see
 * {@code GradingRpcServiceImpl#gradeSubjective}.
 */
public interface AiGradingRpcService {

    /**
     * AI-grade a subjective question submission.
     *
     * @param request grading request; must not be {@code null}. Implementations
     *                should validate {@code submissionId} and {@code tenantId}.
     * @return AI grading result; never {@code null}. On failure throw a
     *         {@link RuntimeException} so the consumer can degrade gracefully.
     */
    AiGradingResult grade(AiGradingRequest request);
}
