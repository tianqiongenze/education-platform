package com.ep.sp07.mcp.service;

import java.util.Map;

/**
 * P2-5 (audit D-048): common contract for knowledge tool operations, allowing
 * the real Dubbo-backed implementation ({@link KnowledgeToolService}) and the
 * dev-only mock ({@link MockKnowledgeToolService}) to be used interchangeably
 * by {@link com.ep.sp07.mcp.tools.KnowledgeTools}.
 */
public interface KnowledgeToolPort {

    Map<String, Object> searchKnowledge(String query, String tenantId);

    Map<String, Object> gradeSubmission(String submissionId, String tenantId);

    Map<String, Object> generateQuiz(String topic, int count, String tenantId);
}
