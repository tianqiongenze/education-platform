package com.ep.knowledge.api.agent;

/**
 * Dubbo RPC service for agent orchestration.
 */
public interface AgentRpcService {

    AgentDto createAgent(AgentCmd cmd);

    AgentChatResult chat(AgentChatCmd cmd);
}
