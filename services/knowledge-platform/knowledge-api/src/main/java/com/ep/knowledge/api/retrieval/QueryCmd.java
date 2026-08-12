package com.ep.knowledge.api.retrieval;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Retrieval query command.
 *
 * @param queryEmbedding precomputed embedding vector for the query; empty list
 *                       when no embedding has been produced (backward compatible)
 */
public record QueryCmd(
        String tenantId,
        String query,
        List<String> knowledgeBaseIds,
        int topK,
        Map<String, Object> filters,
        List<Float> queryEmbedding
) implements Serializable {

    /**
     * Backward-compatible canonical constructor: defaults queryEmbedding to empty.
     */
    public QueryCmd(String tenantId, String query, List<String> knowledgeBaseIds,
                    int topK, Map<String, Object> filters) {
        this(tenantId, query, knowledgeBaseIds, topK, filters, List.of());
    }
}
