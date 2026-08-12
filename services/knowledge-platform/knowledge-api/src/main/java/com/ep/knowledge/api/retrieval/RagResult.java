package com.ep.knowledge.api.retrieval;

import java.io.Serializable;
import java.util.List;

/**
 * RAG result with answer + citations.
 */
public record RagResult(
        String answer,
        List<Citation> citations,
        long elapsedMillis
) implements Serializable {
}
