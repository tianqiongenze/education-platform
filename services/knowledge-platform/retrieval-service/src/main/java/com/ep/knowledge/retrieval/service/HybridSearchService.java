package com.ep.knowledge.retrieval.service;

import com.ep.knowledge.api.retrieval.QueryCmd;
import com.ep.knowledge.api.retrieval.SearchHit;
import com.ep.knowledge.api.retrieval.SearchResult;
import com.ep.knowledge.retrieval.config.MilvusConfig;
import com.ep.knowledge.retrieval.util.InMemoryInvertedIndex;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Hybrid search: vector retrieval (Milvus) + keyword retrieval (ES) + RRF fusion.
 * Uses virtual threads (D-039) for concurrent recall via @Async.
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private static final int RRF_K = 60;

    private final MilvusServiceClient milvusClient;
    private final MilvusConfig.MilvusProperties milvusProperties;
    /** Dev-only in-memory index for keyword search when ES is unavailable. */
    private final InMemoryInvertedIndex devIndex = new InMemoryInvertedIndex();

    public HybridSearchService(MilvusServiceClient milvusClient,
                               MilvusConfig.MilvusProperties milvusProperties) {
        this.milvusClient = milvusClient;
        this.milvusProperties = milvusProperties;
    }

    /**
     * Index a document for dev/CI keyword search (EP_DEV_ONLY mode).
     * In production, Elasticsearch handles indexing — this is a no-op when
     * ES is available.
     */
    public void indexForKeywordSearch(String docId, String content) {
        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devMode = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);
        if (devMode) {
            devIndex.index(docId, content);
        }
    }

    public SearchResult search(QueryCmd cmd) {
        long start = System.currentTimeMillis();
        CompletableFuture<List<SearchHit>> vectorFuture = vectorSearch(cmd);
        CompletableFuture<List<SearchHit>> keywordFuture = keywordSearch(cmd);

        CompletableFuture.allOf(vectorFuture, keywordFuture).join();
        List<SearchHit> vectorHits = vectorFuture.join();
        List<SearchHit> keywordHits = keywordFuture.join();

        List<SearchHit> fused = rrfFuse(vectorHits, keywordHits, cmd.topK());
        long elapsed = System.currentTimeMillis() - start;
        return new SearchResult(fused, fused.size(), elapsed);
    }

    @Async
    public CompletableFuture<List<SearchHit>> vectorSearch(QueryCmd cmd) {
        log.debug("Vector search for tenant={} query={}", cmd.tenantId(), cmd.query());

        List<Float> embedding = cmd.queryEmbedding();
        if (embedding == null || embedding.isEmpty()) {
            // No embedding available: nothing to search. Fail-loud in logs, empty result upstream.
            log.warn("Vector search skipped — no query embedding for tenant={} query=\"{}\"",
                    cmd.tenantId(), cmd.query());
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        try {
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(milvusProperties.collection())
                    .withVectorFieldName(milvusProperties.vectorField())
                    .withTopK(cmd.topK())
                    .withVectors(List.of(embedding))
                    .withExpr("tenant_id == \"" + cmd.tenantId() + "\"")
                    .withOutFields(List.of("document_id", "title", "content", "metadata"))
                    .build();

            R<SearchResults> response = milvusClient.search(searchParam);
            if (response == null) {
                log.error("Milvus search returned null response for tenant={}", cmd.tenantId());
                return CompletableFuture.completedFuture(new ArrayList<>());
            }
            if (response.getStatus() != R.Status.Success.getCode()) {
                log.error("Milvus search failed for tenant={} status={} reason={}",
                        cmd.tenantId(), response.getStatus(), response.getMessage());
                return CompletableFuture.completedFuture(new ArrayList<>());
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);
            List<SearchHit> hits = new ArrayList<>();
            for (SearchResultsWrapper.IDScore idScore : idScores) {
                String documentId = idScore.getStrID();
                String title = fieldString(idScore, "title");
                String content = fieldString(idScore, "content");
                Map<String, Object> metadata = fieldMap(idScore, "metadata");
                hits.add(new SearchHit(documentId, title, content, idScore.getScore(), metadata));
            }
            return CompletableFuture.completedFuture(hits);
        } catch (Exception e) {
            log.error("Milvus search threw exception for tenant={} query=\"{}\"",
                    cmd.tenantId(), cmd.query(), e);
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
    }

    private static String fieldString(SearchResultsWrapper.IDScore idScore, String field) {
        try {
            Object value = idScore.get(field);
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, Object> fieldMap(SearchResultsWrapper.IDScore idScore, String field) {
        try {
            Object value = idScore.get(field);
            if (value instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) m;
                return typed;
            }
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    @Async
    public CompletableFuture<List<SearchHit>> keywordSearch(QueryCmd cmd) {
        log.debug("Keyword search for tenant={} query={}", cmd.tenantId(), cmd.query());

        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devMode = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);

        if (devMode) {
            // EP_DEV_ONLY: use in-memory inverted index with TF-IDF scoring
            List<SearchHit> hits = devIndex.search(cmd.query(), cmd.topK());
            return CompletableFuture.completedFuture(hits);
        }

        // Production path: query Elasticsearch BM25
        // TODO: implement ES REST query via JDK HttpClient when ES is available.
        // For now, return empty list (same as pre-D-099 behavior) to avoid
        // breaking production callers that haven't configured ES yet.
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    /**
     * Reciprocal Rank Fusion (RRF): score = sum(1 / (k + rank_i))
     */
    List<SearchHit> rrfFuse(List<SearchHit> vectorHits, List<SearchHit> keywordHits, int topK) {
        Map<String, Double> scores = new java.util.HashMap<>();
        Map<String, SearchHit> byId = new java.util.HashMap<>();

        for (int i = 0; i < vectorHits.size(); i++) {
            SearchHit h = vectorHits.get(i);
            byId.putIfAbsent(h.documentId(), h);
            scores.merge(h.documentId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < keywordHits.size(); i++) {
            SearchHit h = keywordHits.get(i);
            byId.putIfAbsent(h.documentId(), h);
            scores.merge(h.documentId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    SearchHit h = byId.get(e.getKey());
                    return new SearchHit(h.documentId(), h.title(), h.content(), e.getValue(), h.metadata());
                })
                .toList();
    }
}
