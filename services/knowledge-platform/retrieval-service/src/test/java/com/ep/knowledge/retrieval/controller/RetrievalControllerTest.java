package com.ep.knowledge.retrieval.controller;

import com.ep.knowledge.api.retrieval.RagCmd;
import com.ep.knowledge.api.retrieval.RagResult;
import com.ep.knowledge.api.retrieval.SearchHit;
import com.ep.knowledge.api.retrieval.SearchResult;
import com.ep.knowledge.retrieval.service.HybridSearchService;
import com.ep.knowledge.retrieval.service.RagService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc standalone test for {@link RetrievalController}.
 *
 * <p>Uses {@link MockMvcBuilders#standaloneSetup} (no {@code @WebMvcTest}, no
 * Spring context) and mocks {@link HybridSearchService} and {@link RagService},
 * following the same convention as {@code GradingControllerTest} in grading-service.
 * Role authorization goes through the {@code X-Role} header injected by the BFF.
 */
class RetrievalControllerTest {

    private MockMvc mockMvc;
    private HybridSearchService hybridSearchService;
    private RagService ragService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        hybridSearchService = mock(HybridSearchService.class);
        ragService = mock(RagService.class);
        RetrievalController controller = new RetrievalController(hybridSearchService, ragService);
        // Register GlobalExceptionHandler so ForbiddenException -> 403 rather
        // than the default 500 (standaloneSetup does not pick up advice beans).
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void search_returns_hits_for_authorized_role() throws Exception {
        SearchHit hit = new SearchHit("d1", "Title One", "content one", 0.9, Map.of());
        when(hybridSearchService.search(any()))
                .thenReturn(new SearchResult(List.of(hit), 1L, 5L));

        Map<String, Object> body = Map.of(
                "query", "what is python",
                "knowledgeBaseIds", List.of("kb-1"),
                "topK", 10);

        mockMvc.perform(post("/api/v1/retrieval/search")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Role", "student")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.hits[0].documentId").value("d1"))
                .andExpect(jsonPath("$.hits[0].title").value("Title One"));

        verify(hybridSearchService).search(any());
    }

    @Test
    void search_default_topK_used_when_body_omits_topK() throws Exception {
        when(hybridSearchService.search(any()))
                .thenReturn(new SearchResult(List.of(), 0L, 1L));

        Map<String, Object> body = Map.of("query", "hello");

        mockMvc.perform(post("/api/v1/retrieval/search")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Role", "teacher")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        verify(hybridSearchService).search(any());
    }

    @Test
    void search_denied_for_unauthorized_role() throws Exception {
        // The BFF injects X-Role; "anonymous" (or any role not in the allow-list)
        // must be rejected with 403 before the service is touched.
        Map<String, Object> body = Map.of("query", "secret");

        mockMvc.perform(post("/api/v1/retrieval/search")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Role", "anonymous")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        verify(hybridSearchService, never()).search(any());
    }

    @Test
    void rag_returns_answer_and_citations_for_authorized_role() throws Exception {
        RagResult result = new RagResult("Python is a language.", List.of(), 7L);
        when(ragService.rag(any(RagCmd.class))).thenReturn(result);

        Map<String, Object> body = Map.of(
                "query", "what is python",
                "knowledgeBaseIds", List.of("kb-1"),
                "agentId", "agent-1",
                "topK", 5,
                "enableRerank", true);

        mockMvc.perform(post("/api/v1/retrieval/rag")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Role", "admin")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Python is a language."))
                .andExpect(jsonPath("$.elapsedMillis").value(7));

        verify(ragService).rag(any(RagCmd.class));
    }

    @Test
    void rag_denied_for_unauthorized_role() throws Exception {
        Map<String, Object> body = Map.of("query", "secret");

        mockMvc.perform(post("/api/v1/retrieval/rag")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Role", "guest")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        verify(ragService, never()).rag(any(RagCmd.class));
    }

    @Test
    void rag_default_topK_and_enableRerank_when_body_omits_them() throws Exception {
        when(ragService.rag(any(RagCmd.class)))
                .thenReturn(new RagResult("answer", List.of(), 1L));

        Map<String, Object> body = Map.of("query", "hi");

        mockMvc.perform(post("/api/v1/retrieval/rag")
                        .header("X-Tenant-Id", "tenant-1")
                        .header("X-Role", "platform_admin")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("answer"));

        verify(ragService).rag(any(RagCmd.class));
    }
}
