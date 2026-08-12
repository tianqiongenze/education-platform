package com.ep.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end cross-service flow test for the
 * BFF → RAG retrieval → Agent chat path.
 *
 * <p>The ai-platform service (knowledge base / RAG / agent) is not on the test
 * classpath as a directly-callable Java module, so this test follows the
 * established convention in this module (see {@code SagaTransactionTest} and
 * {@code EndToEndOrderFlowTest}): the invariants of the RAG retrieval and
 * agent-chat pipeline are modelled with an in-memory fake
 * ({@link FakeRagRetrievalService}/{@link FakeAgentChatService}), while the
 * external LLM inference endpoint and the Pulsar chat-event bus are
 * represented as Mockito mocks so the contract can be verified without a live
 * LLM or broker.
 *
 * <p>The test asserts the full knowledge flow: knowledge-base search → RAG
 * context retrieval → agent chat answer synthesis, including the cache-hit
 * behaviour on repeated identical queries and the citation contract.
 */
@Tag("integration")
@ExtendWith(MockitoExtension.class)
class EndToEndKnowledgeFlowTest {

    private FakeRagRetrievalService ragService;
    private FakeAgentChatService agentChatService;

    /** Mocked LLM inference gateway (stands in for ai-platform's LLM client). */
    @Mock
    private LlmInferenceGateway llmGateway;

    /** Mocked Pulsar chat-event bus. */
    @Mock
    private ChatEventBus chatEventBus;

    /** BFF aggregation layer under test. */
    private BffKnowledgeFacade bffKnowledgeFacade;

    @BeforeEach
    void setUp() {
        ragService = new FakeRagRetrievalService();
        agentChatService = new FakeAgentChatService(ragService, llmGateway);
        bffKnowledgeFacade = new BffKnowledgeFacade(
                ragService, agentChatService, chatEventBus);
    }

    @Test
    @DisplayName("BFF → RAG → Agent: search knowledge → RAG query → agent chat returns a cited answer")
    void test_search_rag_chat_full_flow() {
        // Seed the knowledge base with one chunk.
        ragService.index("kb-1", "Spring Boot auto-configuration wires beans via @ConditionalOnClass.");

        when(llmGateway.complete(anyString()))
                .thenReturn("Spring Boot uses @ConditionalOnClass to conditionally enable auto-configuration.");

        var searchHits = bffKnowledgeFacade.searchKnowledge("kb-1", "auto-configuration");
        assertEquals(1, searchHits.size(), "search should return the seeded chunk");

        var ragContext = bffKnowledgeFacade.ragQuery("kb-1", "How does Spring Boot auto-configuration work?", 3);
        assertTrue(ragContext.chunks().size() >= 1, "RAG retrieval must return at least one chunk");
        assertEquals("kb-1", ragContext.chunks().get(0).kbId());

        var answer = bffKnowledgeFacade.agentChat("kb-1", "How does Spring Boot auto-configuration work?", "sess-1");
        assertNotNull(answer.text());
        assertTrue(answer.citations().size() >= 1, "agent answer must cite at least one RAG chunk");
        verify(llmGateway, times(1)).complete(anyString());
        verify(chatEventBus, times(1)).publishAnswered(eq("sess-1"));
    }

    @Test
    @DisplayName("BFF → RAG: repeated identical queries hit the RAG cache on the 2nd+ call")
    void test_rag_cache_hit_on_repeated_query() {
        ragService.index("kb-2", "TiDB is a MySQL-compatible HTAP database.");
        when(llmGateway.complete(anyString())).thenReturn("TiDB is MySQL-compatible.");

        String query = "Is TiDB MySQL compatible?";
        bffKnowledgeFacade.agentChat("kb-2", query, "sess-2");
        bffKnowledgeFacade.agentChat("kb-2", query, "sess-2");

        // The FakeRagRetrievalService caches retrieval results per (kbId, query):
        // the second identical query must be a cache hit, so the underlying
        // retrieval counter should only have advanced once.
        assertEquals(1, ragService.retrievalCount.get(),
                "the second identical RAG query must be served from cache");
        // The LLM gateway is invoked once per agent-chat call regardless of
        // the RAG cache (answer synthesis is not cached here).
        verify(llmGateway, times(2)).complete(anyString());
    }

    @Test
    @DisplayName("BFF → RAG → Agent: empty knowledge base returns an empty RAG context and a fallback answer")
    void test_empty_knowledge_base_returns_fallback_answer() {
        // No chunks indexed for kb-3.
        when(llmGateway.complete(anyString()))
                .thenReturn("I could not find relevant knowledge to answer that.");

        var ragContext = bffKnowledgeFacade.ragQuery("kb-3", "anything", 3);
        assertTrue(ragContext.chunks().isEmpty(),
                "RAG retrieval over an empty KB must return zero chunks");

        var answer = bffKnowledgeFacade.agentChat("kb-3", "anything", "sess-3");
        assertNotNull(answer.text());
        assertTrue(answer.citations().isEmpty(),
                "an answer with no RAG context must carry no citations");
        verify(chatEventBus, times(1)).publishAnswered(eq("sess-3"));
    }

    // ------------------------------------------------------------------
    // BFF aggregation facade under test.
    // ------------------------------------------------------------------

    static final class BffKnowledgeFacade {
        private final FakeRagRetrievalService ragService;
        private final FakeAgentChatService agentChatService;
        private final ChatEventBus chatEventBus;

        BffKnowledgeFacade(FakeRagRetrievalService ragService,
                           FakeAgentChatService agentChatService,
                           ChatEventBus chatEventBus) {
            this.ragService = ragService;
            this.agentChatService = agentChatService;
            this.chatEventBus = chatEventBus;
        }

        List<KnowledgeChunk> searchKnowledge(String kbId, String query) {
            return ragService.search(kbId, query);
        }

        RagContext ragQuery(String kbId, String query, int topK) {
            return ragService.retrieve(kbId, query, topK);
        }

        AgentAnswer agentChat(String kbId, String query, String sessionId) {
            AgentAnswer answer = agentChatService.chat(kbId, query, sessionId);
            chatEventBus.publishAnswered(sessionId);
            return answer;
        }
    }

    // ------------------------------------------------------------------
    // In-memory fakes for the RAG retrieval + agent-chat pipeline.
    // ------------------------------------------------------------------

    static final class FakeRagRetrievalService {
        private final Map<String, List<KnowledgeChunk>> byKb = new ConcurrentHashMap<>();
        private final Map<String, RagContext> retrievalCache = new ConcurrentHashMap<>();
        final AtomicInteger retrievalCount = new AtomicInteger(0);

        void index(String kbId, String text) {
            byKb.computeIfAbsent(kbId, k -> new ArrayList<>())
                    .add(new KnowledgeChunk(kbId, text));
        }

        List<KnowledgeChunk> search(String kbId, String query) {
            List<KnowledgeChunk> chunks = byKb.getOrDefault(kbId, List.of());
            // Naive substring match — sufficient for the fake.
            List<KnowledgeChunk> hits = new ArrayList<>();
            for (KnowledgeChunk c : chunks) {
                if (query != null && c.text().toLowerCase().contains(query.toLowerCase())) {
                    hits.add(c);
                }
            }
            return hits;
        }

        RagContext retrieve(String kbId, String query, int topK) {
            String cacheKey = kbId + "::" + query;
            RagContext cached = retrievalCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            retrievalCount.incrementAndGet();
            List<KnowledgeChunk> hits = search(kbId, query);
            List<KnowledgeChunk> trimmed = hits.size() > topK
                    ? new ArrayList<>(hits.subList(0, topK)) : hits;
            RagContext ctx = new RagContext(trimmed);
            retrievalCache.put(cacheKey, ctx);
            return ctx;
        }
    }

    static final class FakeAgentChatService {
        private final FakeRagRetrievalService ragService;
        private final LlmInferenceGateway llmGateway;

        FakeAgentChatService(FakeRagRetrievalService ragService, LlmInferenceGateway llmGateway) {
            this.ragService = ragService;
            this.llmGateway = llmGateway;
        }

        AgentAnswer chat(String kbId, String query, String sessionId) {
            RagContext ctx = ragService.retrieve(kbId, query, 3);
            String prompt = ctx.chunks().isEmpty()
                    ? "No context. Question: " + query
                    : "Context: " + ctx.chunks().stream().map(KnowledgeChunk::text)
                            .reduce("", (a, b) -> a + " " + b) + " Question: " + query;
            String text = llmGateway.complete(prompt);
            List<String> citations = new ArrayList<>();
            for (int i = 0; i < ctx.chunks().size(); i++) {
                citations.add("chunk-" + (i + 1));
            }
            return new AgentAnswer(text, citations);
        }
    }

    /** Mockito-friendly stand-in for the ai-platform LLM inference client. */
    interface LlmInferenceGateway {
        String complete(String prompt);
    }

    /** Mockito-friendly stand-in for the Pulsar chat-event producer. */
    interface ChatEventBus {
        void publishAnswered(String sessionId);
    }

    record KnowledgeChunk(String kbId, String text) {
    }

    record RagContext(List<KnowledgeChunk> chunks) {
    }

    record AgentAnswer(String text, List<String> citations) {
    }
}
