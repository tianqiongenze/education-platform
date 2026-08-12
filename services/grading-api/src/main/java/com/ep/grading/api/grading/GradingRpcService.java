package com.ep.grading.api.grading;

/**
 * D-050 P2-5: sp05 grading Dubbo RPC contract.
 *
 * <p>Closes the audit D-048 P2-5 gap: {@code KnowledgeToolService.gradeSubmission}
 * previously threw {@link UnsupportedOperationException} because no shared
 * grading contract existed between mcp-server and grading-service. This
 * interface is the published contract; the implementation lives in
 * {@code grading-service} as {@code GradingRpcServiceImpl} and is registered
 * with Nacos via {@code @DubboService}.
 *
 * <p>Iron Law 6: Dubbo 3.3.6 + Spring Cloud Alibaba 2025.1.0.0. No Netflix
 * components, no REST/OpenFeign fallback.
 * Iron Law 7: all parameters and return types are {@link java.io.Serializable}
 * records, GraalVM AOT-friendly.
 *
 * <p>Thread-safety: implementations must be stateless and safe for concurrent
 * invocation under Java 21 virtual threads.
 */
public interface GradingRpcService {

    /**
     * Grade a single submission/question.
     *
     * @param request grading request; must not be {@code null}. Implementations
     *                should validate {@code submissionId} and {@code tenantId}.
     * @return grading result; never {@code null}. On failure throw a
     *         {@link RuntimeException} — Dubbo propagates it to the consumer
     *         where it should be caught and degraded (see mcp-server
     *         {@code KnowledgeToolService.gradeSubmission} try/catch).
     */
    GradingResult gradeSubmission(GradingRequest request);
}
