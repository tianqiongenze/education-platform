package com.ep.knowledge.retrieval.rpc;

import com.ep.knowledge.api.retrieval.QueryCmd;
import com.ep.knowledge.api.retrieval.RagCmd;
import com.ep.knowledge.api.retrieval.RagResult;
import com.ep.knowledge.api.retrieval.SearchHit;
import com.ep.knowledge.api.retrieval.SearchResult;
import com.ep.knowledge.retrieval.service.HybridSearchService;
import com.ep.knowledge.retrieval.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure-Mockito unit test for {@link RetrievalRpcServiceImpl}.
 *
 * <p>The RPC impl is a thin Dubbo façade over {@link HybridSearchService} and
 * {@link RagService}; this test verifies it delegates correctly without
 * altering commands or results.
 */
class RetrievalRpcServiceImplTest {

    private HybridSearchService hybridSearchService;
    private RagService ragService;
    private RetrievalRpcServiceImpl rpcService;

    @BeforeEach
    void setUp() {
        hybridSearchService = mock(HybridSearchService.class);
        ragService = mock(RagService.class);
        rpcService = new RetrievalRpcServiceImpl(hybridSearchService, ragService);
    }

    @Test
    void search_delegates_to_hybrid_search_service() {
        SearchHit hit = new SearchHit("d1", "Title", "content", 0.8, Map.of());
        SearchResult expected = new SearchResult(List.of(hit), 1L, 4L);
        when(hybridSearchService.search(any(QueryCmd.class))).thenReturn(expected);

        QueryCmd cmd = new QueryCmd("tenant-1", "q", List.of("kb-1"), 5, Map.of());
        SearchResult result = rpcService.search(cmd);

        assertSame(expected, result);
        assertEquals(1L, result.total());
        assertEquals("d1", result.hits().get(0).documentId());
        verify(hybridSearchService, times(1)).search(cmd);
    }

    @Test
    void ragChat_delegates_to_rag_service() {
        RagResult expected = new RagResult("answer", List.of(), 12L);
        when(ragService.rag(any(RagCmd.class))).thenReturn(expected);

        RagCmd cmd = new RagCmd("tenant-1", "question", List.of(), null, 5, false);
        RagResult result = rpcService.ragChat(cmd);

        assertSame(expected, result);
        assertEquals("answer", result.answer());
        verify(ragService, times(1)).rag(cmd);
    }

    @Test
    void search_passes_command_through_unchanged() {
        when(hybridSearchService.search(any(QueryCmd.class)))
                .thenReturn(new SearchResult(List.of(), 0L, 1L));

        QueryCmd cmd = new QueryCmd("t1", "query", List.of("kb1", "kb2"), 3, Map.of());
        rpcService.search(cmd);

        // Argument captured verbatim — no field mutation by the RPC layer.
        verify(hybridSearchService).search(argThat(c ->
                "t1".equals(c.tenantId())
                        && "query".equals(c.query())
                        && c.knowledgeBaseIds().equals(List.of("kb1", "kb2"))
                        && c.topK() == 3));
    }

    @Test
    void ragChat_passes_command_through_unchanged() {
        when(ragService.rag(any(RagCmd.class)))
                .thenReturn(new RagResult("a", List.of(), 0L));

        RagCmd cmd = new RagCmd("t1", "q", List.of("kb"), "agent-1", 7, true);
        rpcService.ragChat(cmd);

        verify(ragService).rag(argThat(c ->
                "t1".equals(c.tenantId())
                        && "agent-1".equals(c.agentId())
                        && c.topK() == 7
                        && c.enableRerank()));
    }
}
