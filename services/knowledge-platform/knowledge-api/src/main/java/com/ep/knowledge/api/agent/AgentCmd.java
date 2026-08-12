package com.ep.knowledge.api.agent;

import java.io.Serializable;
import java.util.List;

/**
 * Command for creating/updating an agent.
 */
public record AgentCmd(
        String tenantId,
        String name,
        String systemPrompt,
        List<String> knowledgeBaseIds,
        List<String> tools,
        String mode
) implements Serializable {
}
