package com.ep.memory.model;

import java.io.Serializable;
import java.util.Map;

/**
 * D-045 Multi-level memory entry.
 *
 * <p>memoryType: episodic / semantic / procedural</p>
 *
 * <p>All records implement Serializable for Dubbo RPC serialization and
 * D-040 GraalVM native image compatibility.</p>
 */
public record Memory(
        String id,
        String tenantId,
        String userId,
        String content,
        String memoryType,
        double score,
        long createdAt,
        Map<String, Object> metadata
) implements Serializable {
}
