package com.ep.knowledge.agent.service;

import com.ep.knowledge.agent.entity.Agent;
import com.ep.knowledge.api.agent.AgentCmd;
import com.ep.knowledge.api.agent.AgentDto;

import java.util.List;

public interface AgentService {

    AgentDto create(AgentCmd cmd);

    Agent get(String tenantId, String id);

    List<Agent> list(String tenantId);

    void delete(String tenantId, String id);
}
