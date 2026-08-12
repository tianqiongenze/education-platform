package com.ep.knowledge.api.agent;

import java.io.Serializable;
import java.util.List;

/**
 * Agent DTO returned to callers.
 */
public record AgentDto(
        String id,
        String tenantId,
        String name,
        String systemPrompt,
        List<String> knowledgeBaseIds,
        List<String> tools,
        String mode
) implements Serializable {
}
