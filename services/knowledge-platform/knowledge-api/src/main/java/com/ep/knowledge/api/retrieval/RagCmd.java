package com.ep.knowledge.api.retrieval;

import java.io.Serializable;
import java.util.List;

/**
 * RAG chat command.
 */
public record RagCmd(
        String tenantId,
        String query,
        List<String> knowledgeBaseIds,
        String agentId,
        int topK,
        boolean enableRerank
) implements Serializable {
}
