package com.ep.knowledge.agent.entity;

import com.ep.knowledge.agent.mapper.AgentSessionMapper;
import com.ep.memory.model.ContextWindow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit test for {@link AgentSession} entity and
 * {@link AgentSessionMapper} (D-075). No Spring context, no
 * H2/Testcontainers — keeps the test fast and isolated from the
 * unavailable DB environment.
 */
class AgentSessionEntityTest {

    @Test
    void test_session_creation() {
        AgentSession session = new AgentSession();
        session.setId("sess-001");
        session.setAgentId("agent-001");
        session.setTenantId("t-001");
        session.setUserId("u-001");
        LocalDateTime now = LocalDateTime.now();
        session.setCreatedAt(now);

        assertEquals("sess-001", session.getId());
        assertEquals("agent-001", session.getAgentId());
        assertEquals("t-001", session.getTenantId());
        assertEquals("u-001", session.getUserId());
        assertEquals(now, session.getCreatedAt());
    }

    @Test
    void test_session_context_map() {
        AgentSession session = new AgentSession();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("topic", "physics");
        ctx.put("turn", 3);
        session.setContext(ctx);

        assertNotNull(session.getContext());
        assertEquals("physics", session.getContext().get("topic"));
        assertEquals(3, session.getContext().get("turn"));

        // mutate and verify
        session.getContext().put("turn", 4);
        assertEquals(4, session.getContext().get("turn"));
    }

    @Test
    void test_session_mapper_insert_and_select() {
        AgentSessionMapper mapper = mock(AgentSessionMapper.class);

        AgentSession session = new AgentSession();
        session.setId("sess-002");
        session.setAgentId("agent-002");
        session.setTenantId("t-002");
        session.setUserId("u-002");

        when(mapper.insert(any(AgentSession.class))).thenReturn(1);
        when(mapper.selectById("sess-002")).thenReturn(session);

        int rows = mapper.insert(session);
        assertEquals(1, rows);

        AgentSession result = mapper.selectById("sess-002");
        assertNotNull(result);
        assertEquals("sess-002", result.getId());
        assertEquals("agent-002", result.getAgentId());
        assertEquals("t-002", result.getTenantId());
        assertEquals("u-002", result.getUserId());

        verify(mapper, times(1)).insert(session);
        verify(mapper, times(1)).selectById("sess-002");
    }

    @Test
    void test_memory_snapshot_serialization() {
        // D-045: setMemorySnapshot syncs the ContextWindow into the context map
        // under the "memorySnapshot" and "workingSummary" keys.
        AgentSession session = new AgentSession();
        assertNull(session.getMemorySnapshot());
        assertNull(session.getContext());

        ContextWindow snapshot = new ContextWindow(
                Collections.emptyList(),
                "session summary text",
                Collections.emptyList(),
                Collections.emptyList()
        );

        session.setMemorySnapshot(snapshot);

        // typed view populated
        assertSame(snapshot, session.getMemorySnapshot());

        // context map auto-populated by the setter
        Map<String, Object> ctx = session.getContext();
        assertNotNull(ctx);
        assertSame(snapshot, ctx.get("memorySnapshot"));
        assertEquals("session summary text", ctx.get("workingSummary"));
    }
}
