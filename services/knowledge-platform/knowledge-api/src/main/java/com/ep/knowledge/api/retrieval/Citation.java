package com.ep.knowledge.api.retrieval;

import java.io.Serializable;

/**
 * RAG citation reference.
 */
public record Citation(
        String documentId,
        String title,
        String snippet,
        int chunkIndex,
        double score
) implements Serializable {
}
