package com.ep.knowledge.api.retrieval;

import java.io.Serializable;
import java.util.Map;

/**
 * Single search hit.
 */
public record SearchHit(
        String documentId,
        String title,
        String content,
        double score,
        Map<String, Object> metadata
) implements Serializable {
}
