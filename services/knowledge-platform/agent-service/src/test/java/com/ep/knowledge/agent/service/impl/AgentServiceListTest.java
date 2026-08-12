package com.ep.knowledge.agent.service.impl;

import com.ep.knowledge.agent.entity.Agent;
import com.ep.knowledge.agent.service.AgentService;
import com.ep.knowledge.api.agent.AgentCmd;
import com.ep.knowledge.api.agent.AgentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit test for the D-075 Redis-SET index wiring in
 * {@link AgentServiceImpl}.
 *
 * <p>Verifies that {@code create()} adds the agent id to the per-tenant index
 * SET, that {@code list()} reads the SET and batch-loads agents, that
 * {@code delete()} prunes the index, and that {@code list()} filters out
 * stale cross-tenant entries. Follows the same Mockito pattern as
 * {@link com.ep.knowledge.agent.AgentServiceApplicationTest} — no Spring
 * context, no running Redis (D-072/D-075).
 */
class AgentServiceListTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOps;
    private SetOperations<String, Object> setOps;
    private AgentService agentService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        agentService = new AgentServiceImpl(redisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_create_adds_to_index() {
        AgentCmd cmd = new AgentCmd("tenant-1", "TestAgent", "You are helpful",
                List.of(), List.of(), "ROUTER");

        AgentDto result = agentService.create(cmd);

        assertNotNull(result);
        // The agent must be stored under its own key AND added to the SET index.
        verify(valueOps, times(1)).set(anyString(), any(), anyLong(), any());
        verify(setOps, times(1)).add(eq("agent:index:tenant-1"), eq(result.id()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_list_returns_agents_from_index() {
        Agent a1 = new Agent();
        a1.setId("a1");
        a1.setTenantId("tenant-1");
        a1.setName("AgentOne");
        Agent a2 = new Agent();
        a2.setId("a2");
        a2.setTenantId("tenant-1");
        a2.setName("AgentTwo");

        // members() returns the index SET; get() resolves each id to its Agent.
        when(setOps.members("agent:index:tenant-1"))
                .thenReturn(Set.of("a1", "a2"));
        when(valueOps.get("agent:a1")).thenReturn(a1);
        when(valueOps.get("agent:a2")).thenReturn(a2);

        List<Agent> agents = agentService.list("tenant-1");

        assertEquals(2, agents.size());
        assertTrue(agents.stream().anyMatch(a -> "a1".equals(a.getId())));
        assertTrue(agents.stream().anyMatch(a -> "a2".equals(a.getId())));
        verify(setOps, times(1)).members("agent:index:tenant-1");
    }

    @Test
    void test_list_empty_tenant_returns_empty() {
        when(setOps.members("agent:index:unknown")).thenReturn(Set.of());

        List<Agent> agents = agentService.list("unknown");

        assertNotNull(agents);
        assertTrue(agents.isEmpty());
        // No per-agent get() should fire when the index is empty.
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void test_list_null_index_returns_empty() {
        when(setOps.members("agent:index:ghost")).thenReturn(null);

        List<Agent> agents = agentService.list("ghost");

        assertNotNull(agents);
        assertTrue(agents.isEmpty());
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_delete_removes_from_index() {
        Agent a1 = new Agent();
        a1.setId("a1");
        a1.setTenantId("tenant-1");
        when(valueOps.get("agent:a1")).thenReturn(a1);

        agentService.delete("tenant-1", "a1");

        verify(redisTemplate, times(1)).delete("agent:a1");
        // The id must also be pruned from the per-tenant index SET.
        verify(setOps, times(1)).remove("agent:index:tenant-1", "a1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_delete_missing_agent_skips_index() {
        // If the agent does not exist (wrong tenant or already deleted), delete()
        // should be a no-op and must NOT touch the index.
        when(valueOps.get("agent:missing")).thenReturn(null);

        agentService.delete("tenant-1", "missing");

        verify(redisTemplate, never()).delete(anyString());
        verify(setOps, never()).remove(anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_list_filters_wrong_tenant() {
        // Two ids in the index; one resolves to an agent owned by another tenant.
        // The stale entry must be filtered out so list() never leaks data.
        Agent mine = new Agent();
        mine.setId("a1");
        mine.setTenantId("tenant-1");
        Agent foreign = new Agent();
        foreign.setId("a2");
        foreign.setTenantId("tenant-2");

        when(setOps.members("agent:index:tenant-1"))
                .thenReturn(Set.of("a1", "a2"));
        when(valueOps.get("agent:a1")).thenReturn(mine);
        when(valueOps.get("agent:a2")).thenReturn(foreign);

        List<Agent> agents = agentService.list("tenant-1");

        assertEquals(1, agents.size());
        assertEquals("tenant-1", agents.get(0).getTenantId());
        assertEquals("a1", agents.get(0).getId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_list_skips_non_agent_entries() {
        // A non-Agent value (e.g. corrupted JSON) must be skipped, not thrown.
        when(setOps.members("agent:index:tenant-1"))
                .thenReturn(Set.of("a1"));
        when(valueOps.get("agent:a1")).thenReturn("not-an-agent");

        List<Agent> agents = agentService.list("tenant-1");

        assertNotNull(agents);
        assertTrue(agents.isEmpty());
    }
}
