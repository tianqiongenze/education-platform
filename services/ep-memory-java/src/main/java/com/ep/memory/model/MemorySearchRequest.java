package com.ep.memory.model;

import java.io.Serializable;

/**
 * Request payload for {@code MemoryRpcService.search}.
 */
public record MemorySearchRequest(
        String tenantId,
        String userId,
        String query,
        int topK
) implements Serializable {
}
