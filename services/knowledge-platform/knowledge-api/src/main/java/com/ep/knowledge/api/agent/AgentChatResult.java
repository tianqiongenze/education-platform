package com.ep.knowledge.api.agent;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Result of an agent chat turn.
 */
public record AgentChatResult(
        String sessionId,
        String answer,
        List<Map<String, Object>> steps,
        long elapsedMillis
) implements Serializable {
}
