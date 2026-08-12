package com.ep.knowledge.api.retrieval;

import java.io.Serializable;
import java.util.List;

/**
 * Hybrid search result.
 */
public record SearchResult(
        List<SearchHit> hits,
        long total,
        long elapsedMillis
) implements Serializable {
}
