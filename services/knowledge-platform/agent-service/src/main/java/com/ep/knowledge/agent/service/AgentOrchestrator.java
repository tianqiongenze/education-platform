package com.ep.knowledge.agent.service;

import com.ep.knowledge.api.agent.AgentChatCmd;
import com.ep.knowledge.api.agent.AgentChatResult;

/**
 * Orchestration engine: routes between ROUTER / PIPELINE / DEBATE modes.
 */
public interface AgentOrchestrator {

    AgentChatResult orchestrate(AgentChatCmd cmd);
}
