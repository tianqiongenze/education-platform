package com.ep.mcp.service;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * P2-5 (audit D-048): dev-profile mock implementation of {@link KnowledgeToolService}
 * behaviour for local development where Dubbo/Nacos and retrieval-service
 * are not running. Activated by {@code @Profile("dev-mock")}.
 * <p>
 * Iron rule: this is explicitly a dev-only bean (not the default), so production
 * never silently serves mock data. The real implementation in
 * {@link KnowledgeToolService} delegates to Dubbo RPC.
 */
@Service
@Profile("dev-mock")
public class MockKnowledgeToolService implements KnowledgeToolPort {

    @Override
    public Map<String, Object> searchKnowledge(String query, String tenantId) {
        return Map.of(
                "tool", "knowledge.search",
                "tenantId", tenantId,
                "query", query,
                "results", List.of(
                        Map.of("id", "kb-001",
                                "title", "Introduction to Calculus",
                                "score", 0.92,
                                "snippet", "Limits and continuity form the foundation of calculus..."),
                        Map.of("id", "kb-002",
                                "title", "Linear Algebra Basics",
                                "score", 0.85,
                                "snippet", "Vectors, matrices and linear transformations...")
                ),
                "total", 2
        );
    }

    @Override
    public Map<String, Object> gradeSubmission(String submissionId, String tenantId) {
        return Map.of(
                "tool", "submission.grade",
                "tenantId", tenantId,
                "submissionId", submissionId,
                "status", "graded",
                "score", 87,
                "maxScore", 100,
                "feedback", "Good understanding of core concepts; review question 3 on derivatives.",
                "gradedAt", java.time.Instant.now().toString()
        );
    }

    @Override
    public Map<String, Object> generateQuiz(String topic, int count, String tenantId) {
        List<Map<String, Object>> questions = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            questions.add(Map.of(
                    "id", "q-" + i,
                    "question", "Sample question #" + i + " about " + topic + "?",
                    "options", List.of("A. option", "B. option", "C. option", "D. option"),
                    "answer", "A",
                    "explanation", "Explanation for question " + i
            ));
        }
        return Map.of(
                "tool", "quiz.generate",
                "tenantId", tenantId,
                "topic", topic,
                "count", count,
                "questions", questions,
                "generatedAt", java.time.Instant.now().toString()
        );
    }
}
