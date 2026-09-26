package com.ep.memory.model;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Request payload for {@code MemoryRpcService.add}.
 *
 * <p>messages: list of role/content pairs, e.g.
 * {@code [{"role":"user","content":"..."},{"role":"assistant","content":"..."}]}.</p>
 */
public record MemoryAddRequest(
        String tenantId,
        String userId,
        String sessionId,
        List<Map<String, String>> messages
) implements Serializable {
}
