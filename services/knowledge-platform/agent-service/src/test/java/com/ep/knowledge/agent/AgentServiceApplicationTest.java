package com.ep.knowledge.agent;

import com.ep.knowledge.agent.entity.Agent;
import com.ep.knowledge.agent.service.AgentOrchestrator;
import com.ep.knowledge.agent.service.AgentService;
import com.ep.knowledge.api.agent.AgentChatCmd;
import com.ep.knowledge.api.agent.AgentChatResult;
import com.ep.knowledge.api.agent.AgentCmd;
import com.ep.knowledge.api.agent.AgentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit test for {@link com.ep.knowledge.agent.service.impl.AgentServiceImpl} using
 * Mockito — same pattern as {@code TenantServiceApplicationTest}. Avoids
 * {@code @SpringBootTest} because the service constructor-injects a
 * {@code RedisTemplate} and the orchestrator injects a {@code MemoryManager},
 * both of which require a running Redis / memory backend unavailable in dev/CI.
 *
 * <p>See D-072/D-075: persistence-layer logic is covered by the pure-Mockito
 * entity test {@link com.ep.knowledge.agent.entity.AgentSessionEntityTest}
 * and this service test, without needing a Spring context.
 */
class AgentServiceApplicationTest {

    private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;
    private AgentService agentService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(org.springframework.data.redis.core.RedisTemplate.class);
        agentService = new com.ep.knowledge.agent.service.impl.AgentServiceImpl(redisTemplate);
    }

    @Test
    void contextLoads() {
        assertNotNull(agentService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createAgentStoresInRedis() {
        AgentCmd cmd = new AgentCmd("tenant-1", "TestAgent", "You are helpful",
                List.of(), List.of(), "ROUTER");

        // Mock the set operation
        org.springframework.data.redis.core.ValueOperations<String, Object> valueOps =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // D-075: create() now also maintains the per-tenant index SET, so the
        // SetOperations mock must be stubbed to avoid a Mockito null default.
        org.springframework.data.redis.core.SetOperations<String, Object> setOps =
                mock(org.springframework.data.redis.core.SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        AgentDto result = agentService.create(cmd);

        assertNotNull(result);
        assertEquals("tenant-1", result.tenantId());
        assertEquals("TestAgent", result.name());
        assertEquals("ROUTER", result.mode());
        verify(valueOps, times(1)).set(anyString(), any(), anyLong(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getAgentReturnsFromRedis() {
        Agent agent = new Agent();
        agent.setId("a1");
        agent.setTenantId("tenant-1");
        agent.setName("TestAgent");

        org.springframework.data.redis.core.ValueOperations<String, Object> valueOps =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("agent:a1")).thenReturn(agent);

        Agent result = agentService.get("tenant-1", "a1");

        assertNotNull(result);
        assertEquals("TestAgent", result.getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getAgentReturnsNullForWrongTenant() {
        Agent agent = new Agent();
        agent.setId("a1");
        agent.setTenantId("tenant-1");

        org.springframework.data.redis.core.ValueOperations<String, Object> valueOps =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("agent:a1")).thenReturn(agent);

        Agent result = agentService.get("tenant-2", "a1");

        assertNull(result);
    }
}
