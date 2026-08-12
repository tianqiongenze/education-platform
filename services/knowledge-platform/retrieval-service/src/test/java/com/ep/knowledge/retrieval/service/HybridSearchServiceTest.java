package com.ep.knowledge.retrieval.service;

import com.ep.knowledge.api.retrieval.QueryCmd;
import com.ep.knowledge.api.retrieval.SearchHit;
import com.ep.knowledge.api.retrieval.SearchResult;
import com.ep.knowledge.retrieval.config.MilvusConfig;
import io.milvus.client.MilvusServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HybridSearchServiceTest {

    private HybridSearchService service;

    @BeforeEach
    void setUp() {
        MilvusServiceClient mockClient = mock(MilvusServiceClient.class);
        MilvusConfig.MilvusProperties props = new MilvusConfig.MilvusProperties("ep_knowledge", "embedding");
        service = new HybridSearchService(mockClient, props);
    }

    @Test
    void rrfFuseMergesAndRanks() {
        SearchHit v1 = new SearchHit("d1", "doc1", "c1", 0.9, Map.of());
        SearchHit v2 = new SearchHit("d2", "doc2", "c2", 0.8, Map.of());
        SearchHit k1 = new SearchHit("d2", "doc2", "c2", 0.7, Map.of());
        SearchHit k2 = new SearchHit("d3", "doc3", "c3", 0.6, Map.of());

        List<SearchHit> fused = service.rrfFuse(List.of(v1, v2), List.of(k1, k2), 10);

        assertEquals(3, fused.size(), "should fuse 3 unique docs");
        // d2 appears in both lists => highest RRF score
        assertEquals("d2", fused.get(0).documentId());
    }

    @Test
    void searchReturnsEmptyWhenNoHits() {
        QueryCmd cmd = new QueryCmd("t1", "q", List.of(), 5, Map.of());
        SearchResult result = service.search(cmd);
        assertNotNull(result);
        assertTrue(result.hits().isEmpty());
        assertTrue(result.elapsedMillis() >= 0);
    }
}
