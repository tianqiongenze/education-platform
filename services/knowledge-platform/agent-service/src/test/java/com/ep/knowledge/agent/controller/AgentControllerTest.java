package com.ep.knowledge.agent.controller;

import com.ep.knowledge.agent.service.AgentOrchestrator;
import com.ep.knowledge.agent.service.AgentService;
import com.ep.knowledge.api.agent.AgentChatResult;
import com.ep.knowledge.api.agent.AgentCmd;
import com.ep.knowledge.api.agent.AgentDto;
import com.ep.web.common.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc standalone test for {@link AgentController}.
 *
 * <p>Follows the same convention as {@code GradingControllerTest}: no
 * {@code @WebMvcTest}, no Spring context. {@link AgentService} and
 * {@link AgentOrchestrator} are mocked; {@link GlobalExceptionHandler} is
 * registered as controller advice so {@code ForbiddenException} maps to 403.
 */
class AgentControllerTest {

    private MockMvc mockMvc;
    private AgentService agentService;
    private AgentOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        orchestrator = mock(AgentOrchestrator.class);
        AgentController controller = new AgentController(agentService, orchestrator);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void create_returns_dto_for_authorized_admin() throws Exception {
        AgentDto dto = new AgentDto("a1", "tenant-1", "Helper", "You are helpful",
                List.of("kb-1"), List.of("search"), "ROUTER");
        when(agentService.create(any(AgentCmd.class))).thenReturn(dto);

        Map<String, Object> body = Map.of(
                "name", "Helper",
                "systemPrompt", "You are helpful",
                "knowledgeBaseIds", List.of("kb-1"),
                "tools", List.of("search"),
                "mode", "ROUTER");

        mockMvc.perform(post("/api/v1/agents")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Role", "admin")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("a1"))
                .andExpect(jsonPath("$.name").value("Helper"))
                .andExpect(jsonPath("$.mode").value("ROUTER"));

        verify(agentService).create(any(AgentCmd.class));
    }

    @Test
    void create_denied_for_non_admin_role() throws Exception {
        // Only admin/platform_admin can create agents. A "student" role must be
        // rejected with 403 and never reach the service layer.
        Map<String, Object> body = Map.of("name", "Helper");

        mockMvc.perform(post("/api/v1/agents")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Role", "student")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        verify(agentService, never()).create(any(AgentCmd.class));
    }

    @Test
    void create_defaults_mode_to_router_when_body_omits_mode() throws Exception {
        AgentDto dto = new AgentDto("a1", "tenant-1", "Helper", null, List.of(), List.of(), "ROUTER");
        when(agentService.create(any(AgentCmd.class))).thenReturn(dto);

        Map<String, Object> body = Map.of("name", "Helper");

        mockMvc.perform(post("/api/v1/agents")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Role", "platform_admin")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ROUTER"));

        verify(agentService).create(any());
    }

    @Test
    void chat_returns_orchestrator_result_for_student_role() throws Exception {
        AgentChatResult result = new AgentChatResult("sess-1", "Agent reply: hi",
                List.of(Map.of("step", "route")), 9L);
        when(orchestrator.orchestrate(any())).thenReturn(result);

        Map<String, Object> body = Map.of(
                "userId", "u1",
                "message", "hi");

        mockMvc.perform(post("/api/v1/agents/a1/chat")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Role", "student")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sess-1"))
                .andExpect(jsonPath("$.answer").value("Agent reply: hi"))
                .andExpect(jsonPath("$.elapsedMillis").value(9));

        verify(orchestrator).orchestrate(any());
    }

    @Test
    void chat_denied_for_unauthorized_role() throws Exception {
        Map<String, Object> body = Map.of("message", "hi");

        mockMvc.perform(post("/api/v1/agents/a1/chat")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Role", "guest")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        verify(orchestrator, never()).orchestrate(any());
    }
}
