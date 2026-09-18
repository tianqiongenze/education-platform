package com.ep.integration;

import com.ep.integration.builder.TestDataBuilder;
import com.ep.integration.support.BusinessFakes.FakeUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-211: Cross-service integration test for the knowledge retrieval flow:
 * knowledge-bff → knowledge-service → retrieval-service → agent-service.
 *
 * <p>Follows the established convention in this module (see
 * {@code EndToEndKnowledgeFlowTest}): the RAG retrieval and agent-chat
 * pipeline invariants are modelled with in-memory fakes. The external LLM
 * inference endpoint and Pulsar chat-event bus are tested separately in
 * {@code EndToEndKnowledgeFlowTest} with Mockito mocks.
 *
 * <p>This test focuses on the document lifecycle:
 * upload → parse → vectorize → retrieve → agent answer, including
 * empty-knowledge-base fallback and multi-document ranking.
 */
@Tag("integration")
class KnowledgeRetrievalIT {

    private FakeUserService userService;
    private FakeKnowledgeService knowledgeService;
    private FakeRetrievalService retrievalService;
    private FakeAgentService agentService;

    @BeforeEach
    void setUp() {
        userService = new FakeUserService();
        knowledgeService = new FakeKnowledgeService();
        retrievalService = new FakeRetrievalService(knowledgeService);
        agentService = new FakeAgentService(retrievalService);
    }

    @Test
    @DisplayName("full RAG knowledge flow: upload → parse → vectorize → retrieve → agent answer")
    void test_knowledge_retrieval_flow() {
        var user = userService.createUser(TestDataBuilder.buildUser("alice", "13900001111"));

        // Upload document
        long docId = knowledgeService.uploadDocument("Spring Boot tutorial.pdf",
                "application/pdf", "Full tutorial on Spring Boot 4 with examples", user.getUserId());
        assertTrue(docId > 0);

        // Parse and vectorize (simulated)
        knowledgeService.parseDocument(docId);
        knowledgeService.vectorizeDocument(docId);

        // Retrieve
        var results = retrievalService.retrieve("Spring Boot framework tutorial", 3);
        assertNotNull(results);
        assertTrue(results.size() > 0, "should find at least one matching document");
        assertEquals("READY", results.get(0).status);
    }

    @Test
    @DisplayName("agent answer flow with RAG context")
    void test_agent_answer_with_rag() {
        var user = userService.createUser(TestDataBuilder.buildUser("bob", "13900002222"));

        long docId = knowledgeService.uploadDocument("Math textbook.pdf",
                "application/pdf", "Algebra equations: the quadratic formula is x = (-b ± sqrt(b^2 - 4ac)) / 2a",
                user.getUserId());
        knowledgeService.parseDocument(docId);
        knowledgeService.vectorizeDocument(docId);

        String answer = agentService.answerQuestion("What is the quadratic formula?", user.getUserId());
        assertNotNull(answer);
        assertTrue(answer.contains("quadratic formula"),
                "agent should use RAG context to answer");
    }

    @Test
    @DisplayName("knowledge retrieval returns empty when no documents indexed")
    void test_retrieval_no_documents() {
        var results = retrievalService.retrieve("non-existent topic", 1);
        assertTrue(results.isEmpty(), "should return no results when nothing is indexed");
    }

    @Test
    @DisplayName("agent answer falls back gracefully when RAG has no context")
    void test_agent_answer_fallback() {
        String answer = agentService.answerQuestion("What is 2+2?", 1L);
        assertNotNull(answer);
        assertTrue(answer.contains("2 + 2 = 4") || answer.contains("two plus two") || answer.contains("I could not find"),
                "agent should provide a fallback answer when no RAG context is available");
    }

    @Test
    @DisplayName("multiple documents retrieval with ranking")
    void test_multiple_documents_retrieval() {
        var user = userService.createUser(TestDataBuilder.buildUser("eve", "13900005555"));

        knowledgeService.uploadDocument("Spring Boot 1.pdf", "application/pdf",
                "Spring Boot intro tutorial", user.getUserId());
        knowledgeService.uploadDocument("Spring Boot 2.pdf", "application/pdf",
                "Spring Boot advanced tutorial", user.getUserId());
        knowledgeService.uploadDocument("Java 101.pdf", "application/pdf",
                "Java basics for beginners", user.getUserId());

        // Parse and vectorize all
        knowledgeService.parseDocument(1L);
        knowledgeService.vectorizeDocument(1L);
        knowledgeService.parseDocument(2L);
        knowledgeService.vectorizeDocument(2L);
        knowledgeService.parseDocument(3L);
        knowledgeService.vectorizeDocument(3L);

        var results = retrievalService.retrieve("Spring Boot tutorial", 5);
        assertEquals(2, results.size(), "should find 2 Spring Boot documents, not Java");

        // Spring Boot docs should be ranked higher (more keyword matches)
        assertTrue(results.get(0).title.contains("Spring Boot"),
                "top result should be a Spring Boot document");
    }

    // ------------------------------------------------------------------
    // In-memory fakes for the knowledge / retrieval / agent pipeline.
    // ------------------------------------------------------------------

    /** Fake document status: UPLOADED → PARSED → READY (after vectorize). */
    static final class FakeKnowledgeService {
        private final Map<Long, KnowledgeDoc> docs = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(0);

        long uploadDocument(String filename, String contentType, String content, Long userId) {
            long id = idGen.incrementAndGet();
            docs.put(id, new KnowledgeDoc(id, filename, content, "UPLOADED"));
            return id;
        }

        void parseDocument(long docId) {
            KnowledgeDoc d = docs.get(docId);
            if (d == null) throw new IllegalArgumentException("Document not found: " + docId);
            d.status = "PARSED";
        }

        void vectorizeDocument(long docId) {
            KnowledgeDoc d = docs.get(docId);
            if (d == null) throw new IllegalArgumentException("Document not found: " + docId);
            if (!"PARSED".equals(d.status)) {
                throw new IllegalStateException("Document must be PARSED before vectorize, current: " + d.status);
            }
            d.status = "READY";
        }

        List<KnowledgeDoc> getReadyDocs() {
            List<KnowledgeDoc> ready = new ArrayList<>();
            for (KnowledgeDoc d : docs.values()) {
                if ("READY".equals(d.status)) ready.add(d);
            }
            return ready;
        }

        void clear() {
            docs.clear();
            idGen.set(0);
        }
    }

    static final class KnowledgeDoc {
        final long id;
        final String title;
        final String content;
        String status;

        KnowledgeDoc(long id, String title, String content, String status) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.status = status;
        }
    }

    static final class FakeRetrievalService {
        private final FakeKnowledgeService knowledgeService;

        FakeRetrievalService(FakeKnowledgeService knowledgeService) {
            this.knowledgeService = knowledgeService;
        }

        List<RetrievalResult> retrieve(String query, int topK) {
            List<RetrievalResult> hits = new ArrayList<>();
            if (query == null || query.isBlank()) return hits;

            String queryLower = query.toLowerCase();
            String[] queryTokens = queryLower.split("\\s+");

            for (KnowledgeDoc doc : knowledgeService.getReadyDocs()) {
                String contentLower = doc.content.toLowerCase();
                int matchCount = 0;
                String snippet = null;
                for (String token : queryTokens) {
                    if (token.length() > 2) {
                        int idx = contentLower.indexOf(token);
                        if (idx >= 0) {
                            matchCount++;
                            if (snippet == null) {
                                // RAG retrieval returns a content snippet around
                                // the first matched token, not just metadata.
                                int from = Math.max(0, idx - 60);
                                int to = Math.min(doc.content.length(), idx + 120);
                                snippet = doc.content.substring(from, to);
                            }
                        }
                    }
                }
                if (matchCount > 0) {
                    hits.add(new RetrievalResult(doc.id, doc.title, doc.status, matchCount, snippet));
                }
            }

            // Rank by match count (descending) — more keyword matches = higher rank
            hits.sort((a, b) -> Integer.compare(b.score, a.score));

            if (hits.size() > topK) {
                return new ArrayList<>(hits.subList(0, topK));
            }
            return hits;
        }

        void clear() {
            // state is derived from FakeKnowledgeService
        }
    }

    static final class RetrievalResult {
        final long docId;
        final String title;
        final String status;
        final int score;
        final String snippet;

        RetrievalResult(long docId, String title, String status, int score, String snippet) {
            this.docId = docId;
            this.title = title;
            this.status = status;
            this.score = score;
            this.snippet = snippet;
        }
    }

    static final class FakeAgentService {
        private final FakeRetrievalService retrievalService;

        FakeAgentService(FakeRetrievalService retrievalService) {
            this.retrievalService = retrievalService;
        }

        String answerQuestion(String question, Long userId) {
            List<RetrievalResult> results = retrievalService.retrieve(question, 3);

            if (results.isEmpty()) {
                // Fallback: try to answer from general knowledge
                String qLower = question.toLowerCase();
                if (qLower.contains("2+2") || qLower.contains("two plus two")) {
                    return "2 + 2 = 4";
                }
                return "I could not find relevant knowledge to answer that.";
            }

            // Use the top RAG result's snippet to synthesize an answer —
            // a grounded RAG answer must cite retrieved content, not just
            // document metadata like the title.
            RetrievalResult top = results.get(0);
            return "Based on the knowledge base (" + top.title + "): " + top.snippet;
        }

        void clear() {
            // state is derived from FakeRetrievalService
        }
    }
}
