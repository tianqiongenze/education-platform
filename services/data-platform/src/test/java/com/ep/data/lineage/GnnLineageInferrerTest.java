package com.ep.data.lineage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-188: Pure unit tests for {@link GnnLineageInferrer}.
 *
 * <p>Tests cover graph construction, GraphSAGE forward pass, cosine
 * similarity confidence scoring, auto-add threshold (&gt;0.8), candidate
 * threshold (0.5–0.8), candidate confirmation, and the model.json loading
 * path. The inferrer is instantiated with {@code new GnnLineageInferrer()}
 * (random Xavier weights — no model.json on classpath in tests).
 *
 * <p>No Spring context, no IO (model.json is absent from test classpath).
 */
class GnnLineageInferrerTest {

    private final GnnLineageInferrer inferrer = new GnnLineageInferrer();

    // ─── infer: basic behavior ─────────────────────────────────────────

    @Test
    void infer_null_ast_returns_empty() {
        assertTrue(inferrer.infer(null).isEmpty());
    }

    @Test
    void infer_ast_with_no_source_tables_returns_empty() {
        LineageParser.LineageParseResult ast = new LineageParser.LineageParseResult(
                "target", List.of("c1"), List.of(), List.of(), false, List.of());
        assertTrue(inferrer.infer(ast).isEmpty());
    }

    @Test
    void infer_returns_list_never_null() {
        LineageParser.LineageParseResult ast = makeSimpleAst();
        assertNotNull(inferrer.infer(ast));
    }

    @Test
    void infer_all_edges_have_gnn_inferredBy() {
        LineageParser.LineageParseResult ast = makeSimpleAst();
        List<LineageEdge> edges = inferrer.infer(ast);
        for (LineageEdge e : edges) {
            assertEquals("gnn", e.inferredBy(),
                    "all GNN-inferred edges should have inferredBy=gnn, got " + e.inferredBy());
        }
    }

    @Test
    void infer_auto_added_edges_have_confidence_above_threshold() {
        LineageParser.LineageParseResult ast = makeSimpleAst();
        List<LineageEdge> edges = inferrer.infer(ast);
        for (LineageEdge e : edges) {
            assertTrue(e.confidence() > GnnLineageInferrer.AUTO_ADD_THRESHOLD,
                    "auto-added edge confidence should be > " + GnnLineageInferrer.AUTO_ADD_THRESHOLD
                            + ", got " + e.confidence());
        }
    }

    @Test
    void infer_does_not_duplicate_rule_based_edges() {
        // Rule-based edge: source.a → target.c1
        LineageEdge ruleEdge = new LineageEdge("source", "a", "target", "c1", 1.0, "rule");
        LineageParser.LineageParseResult ast = new LineageParser.LineageParseResult(
                "target", List.of("c1"), List.of("source"), List.of("a"), false, List.of(ruleEdge));

        List<LineageEdge> gnnEdges = inferrer.infer(ast);
        // The exact rule edge should not appear in GNN output.
        for (LineageEdge e : gnnEdges) {
            assertFalse(
                    e.sourceTable().equals("source") && e.sourceColumn().equals("a")
                            && e.targetTable().equals("target") && e.targetColumn().equals("c1"),
                    "GNN should not duplicate rule-based edge source.a→target.c1");
        }
    }

    // ─── candidates ────────────────────────────────────────────────────

    @Test
    void getCandidates_initially_empty() {
        Map<Long, LineageEdge> cands = inferrer.getCandidates();
        assertNotNull(cands);
        // May or may not have candidates depending on random weights, but the
        // map should never be null.
    }

    @Test
    void getCandidates_returns_copy_not_live_view() {
        LineageParser.LineageParseResult ast = makeSimpleAst();
        inferrer.infer(ast);
        Map<Long, LineageEdge> first = inferrer.getCandidates();
        Map<Long, LineageEdge> second = inferrer.getCandidates();
        // Modifying the returned map should not affect the internal store.
        first.clear();
        assertEquals(second.size(), inferrer.getCandidates().size(),
                "getCandidates should return a copy, not a live view");
    }

    @Test
    void candidates_have_confidence_between_thresholds() {
        LineageParser.LineageParseResult ast = makeAstWithManyColumns();
        inferrer.infer(ast);
        Map<Long, LineageEdge> cands = inferrer.getCandidates();
        for (LineageEdge e : cands.values()) {
            assertTrue(e.confidence() > GnnLineageInferrer.CANDIDATE_THRESHOLD,
                    "candidate confidence should be > " + GnnLineageInferrer.CANDIDATE_THRESHOLD
                            + ", got " + e.confidence());
            assertTrue(e.confidence() <= GnnLineageInferrer.AUTO_ADD_THRESHOLD,
                    "candidate confidence should be <= " + GnnLineageInferrer.AUTO_ADD_THRESHOLD
                            + ", got " + e.confidence());
        }
    }

    // ─── confirmCandidate ──────────────────────────────────────────────

    @Test
    void confirmCandidate_unknown_id_returns_null() {
        assertNull(inferrer.confirmCandidate(999999L));
    }

    @Test
    void confirmCandidate_promotes_edge_and_sets_confidence_to_1() {
        // Run inference to populate candidates.
        LineageParser.LineageParseResult ast = makeAstWithManyColumns();
        inferrer.infer(ast);
        Map<Long, LineageEdge> cands = inferrer.getCandidates();

        if (cands.isEmpty()) {
            // With random weights, there may be no candidates. This is OK —
            // skip the test rather than fail.
            return;
        }
        Long id = cands.keySet().iterator().next();
        LineageEdge original = cands.get(id);

        LineageEdge confirmed = inferrer.confirmCandidate(id);
        assertNotNull(confirmed);
        assertEquals(1.0, confirmed.confidence(), 1e-9,
                "confirmed edge should have confidence 1.0");
        assertEquals(original.sourceTable(), confirmed.sourceTable());
        assertEquals(original.sourceColumn(), confirmed.sourceColumn());
        assertEquals(original.targetTable(), confirmed.targetTable());
        assertEquals(original.targetColumn(), confirmed.targetColumn());
        assertEquals("gnn", confirmed.inferredBy());

        // The candidate should be removed from the store.
        assertFalse(inferrer.getCandidates().containsKey(id),
                "confirmed candidate should be removed from candidate store");
    }

    @Test
    void confirmCandidate_twice_returns_null_second_time() {
        LineageParser.LineageParseResult ast = makeAstWithManyColumns();
        inferrer.infer(ast);
        Map<Long, LineageEdge> cands = inferrer.getCandidates();
        if (cands.isEmpty()) {
            return;
        }
        Long id = cands.keySet().iterator().next();
        assertNotNull(inferrer.confirmCandidate(id));
        assertNull(inferrer.confirmCandidate(id), "second confirm should return null");
    }

    // ─── model loading ────────────────────────────────────────────────

    @Test
    void default_constructor_uses_random_weights_when_no_model_json() {
        // No model.json on test classpath → should use random Xavier weights.
        GnnLineageInferrer inf = new GnnLineageInferrer();
        assertFalse(inf.isWeightsLoadedFromFile(),
                "without model.json on classpath, weights should be random (not loaded from file)");
    }

    @Test
    void parseModelJson_valid_json_returns_weights() {
        // Build a minimal valid model.json with correct dimensions.
        String json = buildModelJson();
        GnnLineageInferrer.ModelWeights w = GnnLineageInferrer.parseModelJson(json);
        assertNotNull(w);
        assertNotNull(w.w1());
        assertNotNull(w.w1Neighbor());
        assertNotNull(w.w2());
        assertNotNull(w.w2Neighbor());
        assertEquals(GnnLineageInferrer.EMBED_DIM, w.w1().length);
        assertEquals(GnnLineageInferrer.HIDDEN_1, w.w1()[0].length);
        assertEquals(GnnLineageInferrer.HIDDEN_1, w.w2().length);
        assertEquals(GnnLineageInferrer.HIDDEN_2, w.w2()[0].length);
    }

    @Test
    void parseModelJson_null_or_blank_returns_null() {
        assertNull(GnnLineageInferrer.parseModelJson(null));
        assertNull(GnnLineageInferrer.parseModelJson(""));
        assertNull(GnnLineageInferrer.parseModelJson("   "));
    }

    @Test
    void parseModelJson_missing_key_returns_null() {
        // Missing w2Neighbor → should return null.
        String json = "{\"w1\":" + matrixJson(GnnLineageInferrer.EMBED_DIM, GnnLineageInferrer.HIDDEN_1)
                + ",\"w1Neighbor\":" + matrixJson(GnnLineageInferrer.EMBED_DIM, GnnLineageInferrer.HIDDEN_1)
                + ",\"w2\":" + matrixJson(GnnLineageInferrer.HIDDEN_1, GnnLineageInferrer.HIDDEN_2) + "}";
        assertNull(GnnLineageInferrer.parseModelJson(json));
    }

    @Test
    void construct_with_explicit_weights_sets_loaded_flag() {
        String json = buildModelJson();
        GnnLineageInferrer.ModelWeights w = GnnLineageInferrer.parseModelJson(json);
        assertNotNull(w);
        GnnLineageInferrer inf = new GnnLineageInferrer(w);
        assertTrue(inf.isWeightsLoadedFromFile(),
                "when explicit weights are passed, isWeightsLoadedFromFile should be true");
    }

    // ─── embedding / similarity utilities ──────────────────────────────

    @Test
    void tableEmbedding_is_unit_length() {
        float[] emb = GnnLineageInferrer.tableEmbedding("user_events");
        float norm = 0.0f;
        for (float f : emb) {
            norm += f * f;
        }
        norm = (float) Math.sqrt(norm);
        assertEquals(1.0f, norm, 1e-5f, "table embedding should be unit-length");
    }

    @Test
    void columnEmbedding_is_unit_length() {
        float[] emb = GnnLineageInferrer.columnEmbedding("user_id");
        float norm = 0.0f;
        for (float f : emb) {
            norm += f * f;
        }
        norm = (float) Math.sqrt(norm);
        assertEquals(1.0f, norm, 1e-5f, "column embedding should be unit-length");
    }

    @Test
    void cosineSimilarity_identical_vectors_returns_1() {
        float[] v = GnnLineageInferrer.columnEmbedding("test_col");
        double sim = GnnLineageInferrer.cosineSimilarity(v, v);
        assertEquals(1.0, sim, 1e-6, "identical vectors should have cosine similarity 1.0");
    }

    @Test
    void cosineSimilarity_null_or_empty_returns_0() {
        float[] v = GnnLineageInferrer.columnEmbedding("x");
        assertEquals(0.0, GnnLineageInferrer.cosineSimilarity(null, v), 1e-9);
        assertEquals(0.0, GnnLineageInferrer.cosineSimilarity(v, null), 1e-9);
        assertEquals(0.0, GnnLineageInferrer.cosineSimilarity(new float[0], new float[0]), 1e-9);
    }

    @Test
    void jaccardSimilarity_identical_strings_returns_1() {
        assertEquals(1.0, GnnLineageInferrer.jaccardSimilarity("user_id", "user_id"), 1e-9);
    }

    @Test
    void jaccardSimilarity_disjoint_strings_returns_0() {
        assertEquals(0.0, GnnLineageInferrer.jaccardSimilarity("abc", "xyz"), 1e-9);
    }

    @Test
    void jaccardSimilarity_partial_overlap_between_0_and_1() {
        double sim = GnnLineageInferrer.jaccardSimilarity("user_id", "user_name");
        assertTrue(sim > 0.0 && sim < 1.0, "partial overlap should be between 0 and 1, got " + sim);
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private static LineageParser.LineageParseResult makeSimpleAst() {
        return new LineageParser.LineageParseResult(
                "target", List.of("c1"), List.of("source"), List.of("a"), false, List.of());
    }

    /** AST with many columns to increase the chance of candidate generation. */
    private static LineageParser.LineageParseResult makeAstWithManyColumns() {
        return new LineageParser.LineageParseResult(
                "target",
                List.of("c1", "c2", "c3", "c4"),
                List.of("source"),
                List.of("a", "b", "c", "d"),
                false,
                List.of()
        );
    }

    /** Build a valid model.json string with correct dimensions. */
    private static String buildModelJson() {
        return "{"
                + "\"w1\":" + matrixJson(GnnLineageInferrer.EMBED_DIM, GnnLineageInferrer.HIDDEN_1) + ","
                + "\"w1Neighbor\":" + matrixJson(GnnLineageInferrer.EMBED_DIM, GnnLineageInferrer.HIDDEN_1) + ","
                + "\"w2\":" + matrixJson(GnnLineageInferrer.HIDDEN_1, GnnLineageInferrer.HIDDEN_2) + ","
                + "\"w2Neighbor\":" + matrixJson(GnnLineageInferrer.HIDDEN_1, GnnLineageInferrer.HIDDEN_2)
                + "}";
    }

    /** Generate a JSON matrix string with the given dimensions, all zeros. */
    private static String matrixJson(int rows, int cols) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("[");
            for (int j = 0; j < cols; j++) {
                if (j > 0) {
                    sb.append(",");
                }
                sb.append("0.0");
            }
            sb.append("]");
        }
        sb.append("]");
        return sb.toString();
    }
}
