package com.ep.knowledge.retrieval.util;

import com.ep.knowledge.api.retrieval.SearchHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory inverted index for EP_DEV_ONLY keyword search.
 *
 * <p>D-099: Provides a simple TF-IDF based keyword search fallback when
 * Elasticsearch is not available (dev/CI). Thread-safe via
 * {@link ConcurrentHashMap} (D-039: no synchronized blocks).
 *
 * <p>This is a dev-only utility — production keyword search uses Elasticsearch
 * BM25 via {@link com.ep.knowledge.retrieval.service.HybridSearchService}.
 */
public final class InMemoryInvertedIndex {

    /** Document storage: docId → content. */
    private final ConcurrentHashMap<String, String> documents = new ConcurrentHashMap<>();
    /** Token storage: docId → token frequency map. */
    private final ConcurrentHashMap<String, Map<String, Integer>> tokenFreqs = new ConcurrentHashMap<>();
    /** Inverted index: token → set of docIds. */
    private final ConcurrentHashMap<String, List<String>> invertedIndex = new ConcurrentHashMap<>();

    /**
     * Index a document.
     *
     * @param docId   document identifier
     * @param content document text content
     */
    public void index(String docId, String content) {
        if (docId == null || content == null) return;
        documents.put(docId, content);
        Map<String, Integer> freq = tokenize(content);
        tokenFreqs.put(docId, freq);
        for (String token : freq.keySet()) {
            invertedIndex.computeIfAbsent(token, k -> new ArrayList<>());
            List<String> postings = invertedIndex.get(token);
            if (!postings.contains(docId)) {
                postings.add(docId);
            }
        }
    }

    /**
     * Search for documents matching the query, ranked by TF-IDF score.
     *
     * @param query search query
     * @param topK  maximum number of results
     * @return list of SearchHit sorted by score descending
     */
    public List<SearchHit> search(String query, int topK) {
        if (query == null || query.isBlank() || documents.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        int totalDocs = documents.size();
        Map<String, Double> scores = new HashMap<>();

        for (Map.Entry<String, Integer> entry : queryTokens.entrySet()) {
            String token = entry.getKey();
            List<String> postings = invertedIndex.get(token);
            if (postings == null || postings.isEmpty()) continue;

            // IDF = log(N / df)
            double idf = Math.log((double) totalDocs / postings.size());
            if (idf <= 0) idf = 0.1; // floor to avoid zero IDF for terms in all docs

            for (String docId : postings) {
                Map<String, Integer> docFreq = tokenFreqs.get(docId);
                if (docFreq == null) continue;
                Integer tf = docFreq.get(token);
                if (tf == null) continue;

                // TF-IDF score
                double tfidf = tf * idf;
                scores.merge(docId, tfidf, Double::sum);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    String docId = e.getKey();
                    String content = documents.getOrDefault(docId, "");
                    String title = content.length() > 80 ? content.substring(0, 80) : content;
                    return new SearchHit(docId, title, content, e.getValue(), Map.of());
                })
                .toList();
    }

    /**
     * Clear all indexed documents.
     */
    public void clear() {
        documents.clear();
        tokenFreqs.clear();
        invertedIndex.clear();
    }

    /**
     * Get the number of indexed documents.
     */
    public int size() {
        return documents.size();
    }

    /**
     * Tokenize text into lowercase word frequency map.
     */
    private static Map<String, Integer> tokenize(String text) {
        Map<String, Integer> freq = new HashMap<>();
        if (text == null || text.isBlank()) return freq;
        // Split on non-word characters (Unicode-aware)
        String[] tokens = text.toLowerCase().split("[^\\p{L}\\p{N}]+");
        for (String token : tokens) {
            if (token.length() < 2) continue; // skip single chars
            freq.merge(token, 1, Integer::sum);
        }
        return freq;
    }
}
