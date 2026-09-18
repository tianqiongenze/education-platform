package com.ep.knowledge.retrieval.service;

import com.ep.knowledge.api.retrieval.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * RAG pipeline: retrieve -> rerank -> LLM generate -> citation tracing.
 *
 * <p>Generation is delegated to {@link AiClient} (AI Gateway via JDK
 * HttpClient, EP_DEV_ONLY-guarded). Rerank uses a lightweight keyword-overlap
 * scorer so no new dependencies are introduced (D-074/D-075).
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** Max concatenated context length passed to the LLM, in characters. */
    private static final int MAX_CONTEXT_CHARS = 4000;

    private final HybridSearchService hybridSearchService;
    private final AiClient aiClient;

    public RagService(HybridSearchService hybridSearchService, AiClient aiClient) {
        this.hybridSearchService = hybridSearchService;
        this.aiClient = aiClient;
    }

    public RagResult rag(RagCmd cmd) {
        long start = System.currentTimeMillis();

        // 1. Retrieve
        QueryCmd queryCmd = new QueryCmd(cmd.tenantId(), cmd.query(),
                cmd.knowledgeBaseIds(), cmd.topK() > 0 ? cmd.topK() : 5, java.util.Map.of());
        SearchResult retrieval = hybridSearchService.search(queryCmd);

        // 2. Rerank (keyword-overlap scorer)
        List<SearchHit> reranked = cmd.enableRerank() ? rerank(cmd.query(), retrieval.hits()) : retrieval.hits();

        // 3. LLM generate
        String answer = generate(cmd.query(), reranked);

        // 4. Citations
        List<Citation> citations = reranked.stream()
                .map(h -> new Citation(h.documentId(), h.title(), snippet(h.content()), 0, h.score()))
                .toList();

        long elapsed = System.currentTimeMillis() - start;
        return new RagResult(answer, citations, elapsed);
    }

    /**
     * Lightweight keyword-overlap reranker: score each hit by how many query
     * terms appear in its content, sort by score descending, and break ties by
     * the original retrieval score. This replaces the cross-encoder skeleton
     * with a dependency-free approximation (D-074/D-075).
     *
     * <p>Visible for testing.
     *
     * @param query the user query
     * @param hits  retrieval hits
     * @return a new list sorted by query relevance (descending)
     */
    List<SearchHit> rerank(String query, List<SearchHit> hits) {
        if (query == null || query.isBlank() || hits == null || hits.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return new ArrayList<>(hits);
        }
        // Descending by overlap count, then descending by retrieval score.
        // Negate comparisons to avoid the chained .reversed() gotcha (which
        // would flip the primary ordering too).
        return hits.stream()
                .sorted((a, b) -> {
                    int cmp = Integer.compare(overlapCount(queryTerms, b.content()),
                            overlapCount(queryTerms, a.content()));
                    if (cmp != 0) {
                        return cmp;
                    }
                    return Double.compare(b.score(), a.score());
                })
                .toList();
    }

    private String generate(String query, List<SearchHit> context) {
        log.debug("LLM generate for query={} ctxSize={}", query, context.size());
        String systemPrompt = buildSystemPrompt(context);
        return aiClient.chat(systemPrompt, query);
    }

    /**
     * Concatenate hit contents into a single system-prompt frame, capped to
     * {@link #MAX_CONTEXT_CHARS} so the LLM context window is not blown out.
     */
    private String buildSystemPrompt(List<SearchHit> context) {
        if (context == null || context.isEmpty()) {
            return "You are a helpful assistant. No relevant context was retrieved.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful assistant. Answer the user's question using "
                + "only the context below. If the context is insufficient, say so.\n\n"
                + "Context:\n");
        for (SearchHit hit : context) {
            if (sb.length() >= MAX_CONTEXT_CHARS) {
                break;
            }
            sb.append("- [").append(hit.title()).append("] ")
                    .append(hit.content() == null ? "" : hit.content()).append('\n');
        }
        return sb.toString();
    }

    private static List<String> tokenize(String query) {
        List<String> terms = new ArrayList<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (!token.isBlank()) {
                terms.add(token);
            }
        }
        return terms;
    }

    private static int overlapCount(List<String> queryTerms, String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        int count = 0;
        for (String term : queryTerms) {
            if (lower.contains(term)) {
                count++;
            }
        }
        return count;
    }

    private String snippet(String content) {
        if (content == null) return "";
        return content.length() > 200 ? content.substring(0, 200) + "..." : content;
    }
}
