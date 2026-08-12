package com.ep.knowledge.retrieval.util;

import com.ep.knowledge.api.retrieval.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link InMemoryInvertedIndex} — verifies indexing, TF-IDF
 * search, top-K limiting, and thread safety.
 */
class InMemoryInvertedIndexTest {

    private InMemoryInvertedIndex index;

    @BeforeEach
    void setUp() {
        index = new InMemoryInvertedIndex();
    }

    @Test
    void search_emptyIndex_returnsEmptyList() {
        List<SearchHit> results = index.search("test", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void search_blankQuery_returnsEmptyList() {
        index.index("doc1", "hello world");
        List<SearchHit> results = index.search("", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void search_singleDocument_returnsMatch() {
        index.index("doc1", "the quick brown fox jumps over the lazy dog");
        List<SearchHit> results = index.search("fox", 10);
        assertEquals(1, results.size());
        assertEquals("doc1", results.get(0).documentId());
        assertTrue(results.get(0).score() > 0);
    }

    @Test
    void search_tfIdfScoring_moreRelevantDocScoresHigher() {
        index.index("doc1", "machine learning machine learning algorithms");
        index.index("doc2", "cooking recipes for beginners");

        List<SearchHit> results = index.search("machine learning", 10);
        assertEquals(1, results.size());
        assertEquals("doc1", results.get(0).documentId());
    }

    @Test
    void search_topKLimitsResults() {
        index.index("doc1", "java programming basics");
        index.index("doc2", "java advanced topics");
        index.index("doc3", "java enterprise edition");

        List<SearchHit> results = index.search("java", 2);
        assertEquals(2, results.size());
    }

    @Test
    void search_resultsSortedByScoreDescending() {
        index.index("doc1", "rare word appears once here");
        index.index("doc2", "rare word rare word rare word appears many times");
        index.index("doc3", "rare word appears twice rare word");

        List<SearchHit> results = index.search("rare word", 10);
        assertTrue(results.size() >= 2);
        // doc2 has "rare word" 3 times → highest TF → should be first
        assertEquals("doc2", results.get(0).documentId());
        // Scores should be descending
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).score() >= results.get(i).score(),
                    "Scores not descending: " + results.get(i - 1).score() + " < " + results.get(i).score());
        }
    }

    @Test
    void clear_removesAllDocuments() {
        index.index("doc1", "hello world");
        assertEquals(1, index.size());
        index.clear();
        assertEquals(0, index.size());
        assertTrue(index.search("hello", 10).isEmpty());
    }

    @Test
    void index_nullInputs_areIgnored() {
        index.index(null, "content");
        index.index("doc1", null);
        assertEquals(0, index.size());
    }

    @Test
    void search_noMatchingTokens_returnsEmptyList() {
        index.index("doc1", "hello world");
        List<SearchHit> results = index.search("nonexistent", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void search_multiTokenQuery_combinesScores() {
        index.index("doc1", "java spring boot microservices");
        index.index("doc2", "python django flask");

        List<SearchHit> results = index.search("java spring", 10);
        assertEquals(1, results.size());
        assertEquals("doc1", results.get(0).documentId());
    }

    @Test
    void concurrentAccess_threadSafe() throws InterruptedException {
        int threads = 10;
        Thread[] t = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            t[i] = new Thread(() -> {
                index.index("doc" + idx, "concurrent test document number " + idx);
                index.search("concurrent", 5);
            });
        }
        for (Thread thread : t) thread.start();
        for (Thread thread : t) thread.join();

        assertEquals(threads, index.size());
        List<SearchHit> results = index.search("concurrent", 10);
        assertEquals(threads, results.size());
    }

    @Test
    void search_singleCharTokens_skipped() {
        index.index("doc1", "a b c d e hello world");
        // "a", "b", etc. are single chars → skipped; "hello" and "world" are indexed
        List<SearchHit> results = index.search("hello", 10);
        assertEquals(1, results.size());
        assertEquals("doc1", results.get(0).documentId());
    }
}
