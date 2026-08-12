package com.ep.knowledge.retrieval.service;

import com.ep.knowledge.api.retrieval.QueryCmd;
import com.ep.knowledge.api.retrieval.RagCmd;
import com.ep.knowledge.api.retrieval.RagResult;
import com.ep.knowledge.api.retrieval.SearchHit;
import com.ep.knowledge.api.retrieval.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit test for {@link RagService}.
 *
 * <p>Mocks {@link HybridSearchService} and {@link AiClient} so the RAG
 * pipeline is exercised end-to-end without Redis/Milvus/SP-04. Follows the
 * same Mockito-only pattern as {@code AgentServiceApplicationTest} and
 * {@link HybridSearchServiceTest} (D-072/D-075).
 */
class RagServiceTest {

    private HybridSearchService hybridSearchService;
    private AiClient aiClient;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        hybridSearchService = mock(HybridSearchService.class);
        aiClient = mock(AiClient.class);
        ragService = new RagService(hybridSearchService, aiClient);
    }

    private SearchHit hit(String id, String title, String content, double score) {
        return new SearchHit(id, title, content, score, Map.of());
    }

    @Test
    void test_rag_returns_answer_and_citations() {
        SearchHit h1 = hit("d1", "Title One", "Python is a programming language", 0.9);
        when(hybridSearchService.search(any(QueryCmd.class)))
                .thenReturn(new SearchResult(List.of(h1), 1L, 5L));
        when(aiClient.chat(anyString(), anyString())).thenReturn("Python is a high-level language.");

        RagCmd cmd = new RagCmd("tenant-1", "what is python", List.of("kb-1"),
                null, 5, false);
        RagResult result = ragService.rag(cmd);

        assertNotNull(result);
        assertEquals("Python is a high-level language.", result.answer());
        assertEquals(1, result.citations().size());
        assertEquals("d1", result.citations().get(0).documentId());
        assertEquals("Title One", result.citations().get(0).title());
    }

    @Test
    void test_rerank_reorders_by_query_relevance() {
        // d1 has 2 query-term overlaps; d2 has 0; d2 has the higher retrieval
        // score. Keyword-overlap rerank must therefore promote d1 above d2.
        SearchHit d2 = hit("d2", "Unrelated", "The weather is sunny today", 0.95);
        SearchHit d1 = hit("d1", "Physics", "Machine learning models train on data", 0.5);

        List<SearchHit> reranked = ragService.rerank("machine learning data", List.of(d2, d1));

        assertEquals(2, reranked.size());
        assertEquals("d1", reranked.get(0).documentId(),
                "the hit matching more query terms should rank first");
        assertEquals("d2", reranked.get(1).documentId());
    }

    @Test
    void test_rerank_breaks_ties_by_retrieval_score() {
        // Two hits with identical content (same overlap) — higher retrieval
        // score must win.
        SearchHit low = hit("d1", "A", "machine learning data", 0.4);
        SearchHit high = hit("d2", "B", "machine learning data", 0.8);

        List<SearchHit> reranked = ragService.rerank("machine learning", List.of(low, high));

        assertEquals("d2", reranked.get(0).documentId());
        assertEquals("d1", reranked.get(1).documentId());
    }

    @Test
    void test_rerank_empty_query_returns_empty() {
        SearchHit h1 = hit("d1", "T", "content", 0.9);
        List<SearchHit> reranked = ragService.rerank("", List.of(h1));
        assertNotNull(reranked);
        assertTrue(reranked.isEmpty());
    }

    @Test
    void test_rerank_no_overlaps_preserves_order() {
        // When no query term overlaps anything, the comparator falls back to
        // retrieval score, so a higher-score hit should still come first.
        SearchHit low = hit("d1", "A", "alpha", 0.3);
        SearchHit high = hit("d2", "B", "beta", 0.7);

        List<SearchHit> reranked = ragService.rerank("gamma delta", List.of(low, high));

        assertEquals(2, reranked.size());
        assertEquals("d2", reranked.get(0).documentId());
        assertEquals("d1", reranked.get(1).documentId());
    }

    @Test
    void test_generate_calls_ai_client_with_context() {
        SearchHit h1 = hit("d1", "Title One", "Python is a programming language", 0.9);
        when(hybridSearchService.search(any(QueryCmd.class)))
                .thenReturn(new SearchResult(List.of(h1), 1L, 5L));
        when(aiClient.chat(anyString(), anyString())).thenReturn("answer");

        RagCmd cmd = new RagCmd("tenant-1", "what is python", List.of("kb-1"),
                null, 5, false);
        ragService.rag(cmd);

        // The system prompt must carry the retrieved context, and the user
        // message must be the original query.
        verify(aiClient, times(1)).chat(
                argThat(sys -> sys.contains("Python is a programming language")
                        && sys.contains("Title One")),
                eq("what is python"));
    }

    @Test
    void test_empty_search_results_handled_gracefully() {
        when(hybridSearchService.search(any(QueryCmd.class)))
                .thenReturn(new SearchResult(List.of(), 0L, 1L));
        when(aiClient.chat(anyString(), anyString())).thenReturn("no context answer");

        RagCmd cmd = new RagCmd("tenant-1", "anything", List.of(), null, 5, false);
        RagResult result = ragService.rag(cmd);

        assertNotNull(result);
        assertEquals("no context answer", result.answer());
        assertTrue(result.citations().isEmpty());
        // The system prompt must still be sent (with the empty-context frame).
        verify(aiClient, times(1)).chat(
                argThat(sys -> sys.contains("No relevant context was retrieved")),
                eq("anything"));
    }

    @Test
    void test_rag_includes_latency() {
        when(hybridSearchService.search(any(QueryCmd.class)))
                .thenReturn(new SearchResult(List.of(), 0L, 1L));
        when(aiClient.chat(anyString(), anyString())).thenReturn("answer");

        RagCmd cmd = new RagCmd("tenant-1", "q", List.of(), null, 5, false);
        RagResult result = ragService.rag(cmd);

        assertTrue(result.elapsedMillis() >= 0,
                "elapsedMillis should be non-negative, got " + result.elapsedMillis());
    }

    @Test
    void test_rag_with_rerank_enabled_applies_reranker() {
        SearchHit d2 = hit("d2", "Unrelated", "weather is sunny", 0.99);
        SearchHit d1 = hit("d1", "Topic", "machine learning data", 0.5);
        when(hybridSearchService.search(any(QueryCmd.class)))
                .thenReturn(new SearchResult(List.of(d2, d1), 2L, 5L));
        when(aiClient.chat(anyString(), anyString())).thenReturn("answer");

        RagCmd cmd = new RagCmd("tenant-1", "machine learning data", List.of(),
                null, 5, true);
        RagResult result = ragService.rag(cmd);

        // Rerank on, so d1 (more query-term overlap) should be the first citation.
        assertEquals("d1", result.citations().get(0).documentId());
    }
}
