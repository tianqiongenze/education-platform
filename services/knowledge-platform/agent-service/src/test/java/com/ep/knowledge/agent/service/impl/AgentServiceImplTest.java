package com.ep.knowledge.agent.service.impl;

import com.ep.knowledge.agent.entity.Agent;
import com.ep.knowledge.agent.service.AgentService;
import com.ep.knowledge.api.agent.AgentCmd;
import com.ep.knowledge.api.agent.AgentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mockito-extension unit test for {@link AgentServiceImpl} covering
 * {@code create()} and {@code get()}. The Redis-SET index behaviour of
 * {@code list()}/{@code delete()} is covered separately by
 * {@link AgentServiceListTest}; this test focuses on agent creation (UUID id
 * generation, default mode, 30-day TTL, index update) and tenant-scoped lookup.
 *
 * <p>Uses {@code @ExtendWith(MockitoExtension.class)} + {@code @Mock} per the
 * knowledge-platform service-test convention.
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceImplTest {

    @Mock
    @SuppressWarnings("unchecked")
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOps;
    @Mock
    private SetOperations<String, Object> setOps;

    private AgentService agentService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
        agentService = new AgentServiceImpl(redisTemplate);
    }

    @Test
    void create_assigns_uuid_id_and_persists_with_30_day_ttl() {
        AgentCmd cmd = new AgentCmd("tenant-1", "Helper", "You are helpful",
                List.of("kb-1"), List.of("search"), "ROUTER");

        AgentDto result = agentService.create(cmd);

        assertNotNull(result);
        assertNotNull(result.id());
        assertEquals("tenant-1", result.tenantId());
        assertEquals("Helper", result.name());
        assertEquals("You are helpful", result.systemPrompt());
        assertEquals(List.of("kb-1"), result.knowledgeBaseIds());
        assertEquals(List.of("search"), result.tools());
        assertEquals("ROUTER", result.mode());

        // The agent must be stored under its own key for 30 days...
        verify(valueOps, times(1)).set(anyString(), any(Agent.class), eq(30L), eq(TimeUnit.DAYS));
        // ...and its id added to the per-tenant index SET.
        verify(setOps, times(1)).add(eq("agent:index:tenant-1"), eq(result.id()));
    }

    @Test
    void create_defaults_mode_to_router_when_cmd_mode_is_null() {
        AgentCmd cmd = new AgentCmd("tenant-1", "Helper", null, List.of(), List.of(), null);

        AgentDto result = agentService.create(cmd);

        assertEquals("ROUTER", result.mode());
    }

    @Test
    void get_returns_agent_when_tenant_matches() {
        Agent agent = new Agent();
        agent.setId("a1");
        agent.setTenantId("tenant-1");
        agent.setName("Helper");
        when(valueOps.get("agent:a1")).thenReturn(agent);

        Agent result = agentService.get("tenant-1", "a1");

        assertNotNull(result);
        assertEquals("a1", result.getId());
        assertEquals("Helper", result.getName());
    }

    @Test
    void get_returns_null_for_wrong_tenant() {
        Agent agent = new Agent();
        agent.setId("a1");
        agent.setTenantId("tenant-1");
        when(valueOps.get("agent:a1")).thenReturn(agent);

        // A different tenant must never read another tenant's agent.
        Agent result = agentService.get("tenant-2", "a1");

        assertNull(result);
    }

    @Test
    void get_returns_null_when_key_missing() {
        when(valueOps.get("agent:missing")).thenReturn(null);

        assertNull(agentService.get("tenant-1", "missing"));
    }

    @Test
    void get_returns_null_for_non_agent_value() {
        // Corrupted / non-Agent Redis value must be tolerated, not thrown.
        when(valueOps.get("agent:junk")).thenReturn("not-an-agent");

        assertNull(agentService.get("tenant-1", "junk"));
    }
}
