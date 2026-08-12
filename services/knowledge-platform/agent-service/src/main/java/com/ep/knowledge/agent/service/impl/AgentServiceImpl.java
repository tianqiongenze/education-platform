package com.ep.knowledge.agent.service.impl;

import com.ep.knowledge.agent.entity.Agent;
import com.ep.knowledge.agent.service.AgentService;
import com.ep.knowledge.api.agent.AgentCmd;
import com.ep.knowledge.api.agent.AgentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);
    private static final String KEY_PREFIX = "agent:";
    /**
     * D-075: Redis SET index per tenant so {@link #list(String)} can read the
     * member IDs in one round-trip instead of scanning keys by prefix. Key
     * format: {@code agent:index:{tenantId}}.
     */
    private static final String INDEX_PREFIX = "agent:index:";

    private final RedisTemplate<String, Object> redisTemplate;

    public AgentServiceImpl(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public AgentDto create(AgentCmd cmd) {
        String id = UUID.randomUUID().toString();
        Agent agent = new Agent();
        agent.setId(id);
        agent.setTenantId(cmd.tenantId());
        agent.setName(cmd.name());
        agent.setSystemPrompt(cmd.systemPrompt());
        agent.setKnowledgeBaseIds(cmd.knowledgeBaseIds());
        agent.setTools(cmd.tools());
        agent.setMode(cmd.mode() == null ? "ROUTER" : cmd.mode());

        redisTemplate.opsForValue().set(KEY_PREFIX + id, agent, 30, TimeUnit.DAYS);
        // D-075: maintain a Redis SET index per tenant so list() can avoid a
        // KEYS/SCAN-based lookup (which is O(N) and blocks Redis).
        redisTemplate.opsForSet().add(INDEX_PREFIX + cmd.tenantId(), id);
        log.info("Created agent {} for tenant {}", id, cmd.tenantId());
        return toDTO(agent);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Agent get(String tenantId, String id) {
        Object raw = redisTemplate.opsForValue().get(KEY_PREFIX + id);
        if (raw instanceof Agent a && tenantId.equals(a.getTenantId())) {
            return a;
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Agent> list(String tenantId) {
        // D-075: read the per-tenant index SET, then batch-get each agent by ID.
        // Avoids KEYS/SCAN (O(N), blocks Redis). The SET is maintained in
        // create() and pruned in delete() / get()-based filtering below.
        Set<Object> ids = redisTemplate.opsForSet().members(INDEX_PREFIX + tenantId);
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        List<Agent> agents = new ArrayList<>(ids.size());
        for (Object rawId : ids) {
            if (rawId == null) {
                continue;
            }
            String id = rawId.toString();
            Object raw = redisTemplate.opsForValue().get(KEY_PREFIX + id);
            // Defensive tenant filter: stale entries from a different tenant
            // (e.g. after tenant reassignment) are silently skipped.
            if (raw instanceof Agent a && tenantId.equals(a.getTenantId())) {
                agents.add(a);
            }
        }
        return agents;
    }

    @Override
    public void delete(String tenantId, String id) {
        Agent a = get(tenantId, id);
        if (a != null) {
            redisTemplate.delete(KEY_PREFIX + id);
            // D-075: prune the per-tenant index entry to keep list() accurate.
            redisTemplate.opsForSet().remove(INDEX_PREFIX + tenantId, id);
        }
    }

    private AgentDto toDTO(Agent a) {
        return new AgentDto(a.getId(), a.getTenantId(), a.getName(), a.getSystemPrompt(),
                a.getKnowledgeBaseIds(), a.getTools(), a.getMode());
    }
}
