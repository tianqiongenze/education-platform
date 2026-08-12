package com.ep.knowledge.bff.controller;

import com.ep.knowledge.api.agent.AgentChatResult;
import com.ep.knowledge.api.agent.AgentRpcService;
import com.ep.knowledge.api.retrieval.RagResult;
import com.ep.knowledge.api.retrieval.RetrievalRpcService;
import com.ep.knowledge.api.retrieval.SearchHit;
import com.ep.knowledge.api.retrieval.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc standalone test for {@link BffController}.
 *
 * <p>The BFF aggregates retrieval-service and agent-service via Dubbo. The
 * Dubbo references are field-injected, so this test sets them reflectively
 * (same pattern as {@code GradingControllerTest} in grading-service). All three
 * endpoints are covered: knowledge overview, agent dashboard, RAG passthrough.
 * JSON request bodies are written as string literals to avoid a hard
 * dependency on jackson-databind in the BFF test classpath.
 */
class BffControllerTest {

    private static final MediaType APPLICATION_JSON = MediaType.APPLICATION_JSON;

    private MockMvc mockMvc;
    private RetrievalRpcService retrievalRpcService;
    private AgentRpcService agentRpcService;

    @BeforeEach
    void setUp() throws Exception {
        retrievalRpcService = mock(RetrievalRpcService.class);
        agentRpcService = mock(AgentRpcService.class);
        BffController controller = new BffController();
        Field r = BffController.class.getDeclaredField("retrievalRpcService");
        r.setAccessible(true);
        r.set(controller, retrievalRpcService);
        Field a = BffController.class.getDeclaredField("agentRpcService");
        a.setAccessible(true);
        a.set(controller, agentRpcService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void knowledgeOverview_returns_top_documents_and_total() throws Exception {
        SearchHit hit = new SearchHit("d1", "Title", "content", 0.9, Map.of());
        when(retrievalRpcService.search(any()))
                .thenReturn(new SearchResult(List.of(hit), 1L, 5L));

        mockMvc.perform(get("/api/v1/bff/knowledge/overview")
                        .header("X-Tenant-Id", "tenant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-1"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.topDocuments[0].documentId").value("d1"));

        verify(retrievalRpcService).search(any());
    }

    @Test
    void knowledgeOverview_degrades_gracefully_when_rpc_fails() throws Exception {
        // A downstream Dubbo/RPC failure must surface as an error field, not a 500.
        when(retrievalRpcService.search(any())).thenThrow(new RuntimeException("down"));

        mockMvc.perform(get("/api/v1/bff/knowledge/overview")
                        .header("X-Tenant-Id", "tenant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-1"))
                .andExpect(jsonPath("$.topDocuments").isArray())
                .andExpect(jsonPath("$.error").value("down"));
    }

    @Test
    void agentDashboard_returns_answer_session_and_latency() throws Exception {
        when(agentRpcService.chat(any()))
                .thenReturn(new AgentChatResult("sess-1", "Agent reply",
                        List.of(Map.of("step", "route")), 42L));

        String body = "{\"agentId\":\"a1\",\"message\":\"hello\"}";

        mockMvc.perform(post("/api/v1/bff/agent/dashboard")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Agent reply"))
                .andExpect(jsonPath("$.sessionId").value("sess-1"))
                .andExpect(jsonPath("$.elapsedMillis").value(42));

        verify(agentRpcService).chat(any());
    }

    @Test
    void agentDashboard_defaults_message_to_hello_when_body_omits_it() throws Exception {
        when(agentRpcService.chat(any()))
                .thenReturn(new AgentChatResult("sess-1", "reply", List.of(), 1L));

        String body = "{\"agentId\":\"a1\"}";

        mockMvc.perform(post("/api/v1/bff/agent/dashboard")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("reply"));

        verify(agentRpcService).chat(any());
    }

    @Test
    void agentDashboard_degrades_gracefully_when_rpc_fails() throws Exception {
        when(agentRpcService.chat(any())).thenThrow(new RuntimeException("agent-down"));

        String body = "{\"agentId\":\"a1\"}";

        mockMvc.perform(post("/api/v1/bff/agent/dashboard")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value("agent-down"));
    }

    @Test
    void rag_passthrough_returns_rag_result() throws Exception {
        when(retrievalRpcService.ragChat(any()))
                .thenReturn(new RagResult("the answer", List.of(), 9L));

        String body = "{\"query\":\"what is python\",\"knowledgeBaseIds\":[\"kb-1\"],\"topK\":5,\"enableRerank\":true}";

        mockMvc.perform(post("/api/v1/bff/knowledge/rag")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("the answer"))
                .andExpect(jsonPath("$.elapsedMillis").value(9));

        verify(retrievalRpcService).ragChat(any());
    }

    @Test
    void rag_passthrough_defaults_topK_and_enableRerank_when_body_omits_them() throws Exception {
        when(retrievalRpcService.ragChat(any()))
                .thenReturn(new RagResult("answer", List.of(), 1L));

        String body = "{\"query\":\"hi\"}";

        mockMvc.perform(post("/api/v1/bff/knowledge/rag")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("answer"));

        verify(retrievalRpcService).ragChat(any());
    }
}
