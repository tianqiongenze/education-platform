package com.ep.knowledge.api.agent;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Agent chat command.
 */
public record AgentChatCmd(
        String tenantId,
        String agentId,
        String sessionId,
        String userId,
        String message,
        Map<String, Object> parameters
) implements Serializable {
}
