package com.ep.knowledge.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ep.knowledge.agent.entity.AgentSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for {@link AgentSession}.
 * D-075: Completes the agent-service persistence layer — the entity was previously
 * a design placeholder with no Mapper and no datasource configuration.
 */
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSession> {
}
