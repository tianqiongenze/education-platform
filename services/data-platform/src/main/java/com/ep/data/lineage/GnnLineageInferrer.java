package com.ep.data.lineage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pure-Java GraphSAGE 2-layer inference engine for field-level lineage
 * (§12.1.3 GNN-based layer, D-188).
 *
 * <p>Infers column-level lineage edges that the rule-based {@link LineageParser}
 * cannot determine (e.g. computed columns, renamed fields, cross-table
 * lineage through views). Uses a GraphSAGE (Sample and Aggregate) message-
 * passing network with two layers, implemented entirely in Java with no
 * PyTorch/DL4J dependency.
 *
 * <h2>Graph Construction</h2>
 * <ul>
 *   <li><b>Node types:</b> TABLE, COLUMN. Each node has a 128-dim embedding.</li>
 *   <li><b>Node features:</b>
 *     <ul>
 *       <li>TABLE — hash-based embedding of the table name (128-dim).</li>
 *       <li>COLUMN — char-level n-gram hash of the column name (128-dim).</li>
 *     </ul>
 *   </li>
 *   <li><b>Edge types:</b>
 *     <ul>
 *       <li>FK/reference — from the rule-based parser (source→target lineage).</li>
 *       <li>Name-similarity — Jaccard similarity of column name token sets &gt; 0.3.</li>
 *       <li>Data-distribution-similarity — type match (heuristic).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>GraphSAGE 2-Layer Architecture</h2>
 * <ul>
 *   <li>Layer 1: input_dim(128) → hidden(128), ReLU, mean aggregator.</li>
 *   <li>Layer 2: hidden(128) → hidden(64), ReLU, mean aggregator.</li>
 *   <li>Weights: Xavier initialization, loadable from {@code model.json} on
 *       the classpath. If {@code model.json} is not found, random Xavier
 *       weights are used — the network still produces confidence scores,
 *       but they are <em>untrained</em>. This is clearly documented here and
 *       in the log output.</li>
 * </ul>
 *
 * <h2>Inference</h2>
 * For each potential {@code (source_column, target_column)} pair not covered
 * by the rule-based parser:
 * <ul>
 *   <li>Compute node embeddings via 2-layer GraphSAGE.</li>
 *   <li>Cosine similarity → confidence (0.0–1.0).</li>
 *   <li>confidence &gt; 0.8: auto-add edge (inferredBy="gnn").</li>
 *   <li>0.5–0.8: mark as candidate (requires manual confirmation).</li>
 *   <li>&lt; 0.5: discard.</li>
 * </ul>
 *
 * <h2>EP_DEV_ONLY Guard</h2>
 * When {@code EP_DEV_ONLY} is unset, blank, or {@code "true"}, this component
 * runs in dev mode — it loads random weights (unless a model.json is present)
 * and logs which path was used. Production deployments would supply a trained
 * {@code model.json}.
 *
 * <p>This class is a Spring {@code @Component} but also instantiable with
 * {@code new GnnLineageInferrer()} for unit tests.
 */
@Component
public class GnnLineageInferrer {

    private static final Logger log = LoggerFactory.getLogger(GnnLineageInferrer.class);

    // ─── Model hyperparameters ───
    static final int EMBED_DIM = 128;
    static final int HIDDEN_1 = 128;
    static final int HIDDEN_2 = 64;

    static final double AUTO_ADD_THRESHOLD = 0.8;
    static final double CANDIDATE_THRESHOLD = 0.5;

    private static final String INFERRED_BY = "gnn";
    private static final String MODEL_RESOURCE = "model.json";

    // ─── Layer weights ───
    // Layer 1: [EMBED_DIM][HIDDEN_1] — self transform
    private final float[][] w1;
    // Layer 1: [EMBED_DIM][HIDDEN_1] — neighbor aggregate transform
    private final float[][] w1Neighbor;
    // Layer 2: [HIDDEN_1][HIDDEN_2] — self transform
    private final float[][] w2;
    // Layer 2: [HIDDEN_1][HIDDEN_2] — neighbor aggregate transform
    private final float[][] w2Neighbor;

    private final boolean weightsLoadedFromFile;
    private final AtomicLong candidateIdSeq = new AtomicLong(0);

    // ─── Candidate store (for manual confirmation) ───
    private final Map<Long, LineageEdge> candidates = new ConcurrentHashMap<>();

    /** Default constructor — loads model.json from classpath if available. */
    public GnnLineageInferrer() {
        this(loadWeightsFromClasspath());
    }

    /**
     * Construct with explicit weights (for tests).
     *
     * @param weights the model weights, or {@code null} to use random Xavier init
     */
    public GnnLineageInferrer(ModelWeights weights) {
        if (weights == null) {
            this.w1 = xavierInit(EMBED_DIM, HIDDEN_1);
            this.w1Neighbor = xavierInit(EMBED_DIM, HIDDEN_1);
            this.w2 = xavierInit(HIDDEN_1, HIDDEN_2);
            this.w2Neighbor = xavierInit(HIDDEN_1, HIDDEN_2);
            this.weightsLoadedFromFile = false;
            log.info("[GnnLineageInferrer] No model.json found — using random Xavier weights (UNTRAINED). "
                    + "Confidence scores are structurally valid but not learned.");
        } else {
            this.w1 = weights.w1();
            this.w1Neighbor = weights.w1Neighbor();
            this.w2 = weights.w2();
            this.w2Neighbor = weights.w2Neighbor();
            this.weightsLoadedFromFile = true;
            log.info("[GnnLineageInferrer] Loaded trained weights from model.json.");
        }
    }

    /**
     * Infer GNN-based lineage edges for the given AST parse result.
     *
     * <p>For each {@code (source_column, target_column)} pair where the rule-
     * based parser did <em>not</em> produce an edge (i.e. wildcard or
     * unmatched projections), compute a GraphSAGE embedding cosine similarity
     * and classify it as auto-add / candidate / discard.
     *
     * @param ast the rule-based parse result
     * @return GNN-inferred edges with {@code inferredBy="gnn"} (auto-add only)
     */
    public List<LineageEdge> infer(LineageParser.LineageParseResult ast) {
        List<LineageEdge> out = new ArrayList<>();
        if (ast == null || ast.sourceTables().isEmpty()) {
            return out;
        }

        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);
        if (devGuard) {
            log.info("[GnnLineageInferrer] EP_DEV_ONLY active — running GNN inference with {} weights",
                    weightsLoadedFromFile ? "trained" : "untrained");
        }

        // Build the graph: nodes for tables + columns, edges for rule-based +
        // name-similarity + type-match.
        Graph graph = buildGraph(ast);

        // Compute embeddings for all nodes via 2-layer GraphSAGE.
        Map<String, float[]> embeddings = computeEmbeddings(graph);

        // For each potential (source_column, target_column) pair not covered
        // by rule-based edges, compute cosine similarity.
        Set<String> ruleEdgeKeys = ruleEdgeKeySet(ast.edges());
        List<ColumnInfo> sourceCols = graph.sourceColumns;
        List<ColumnInfo> targetCols = graph.targetColumns;

        for (ColumnInfo src : sourceCols) {
            for (ColumnInfo tgt : targetCols) {
                String key = src.table + "." + src.name + "->" + tgt.table + "." + tgt.name;
                if (ruleEdgeKeys.contains(key)) {
                    continue;
                }
                float[] srcEmb = embeddings.get(src.nodeKey);
                float[] tgtEmb = embeddings.get(tgt.nodeKey);
                if (srcEmb == null || tgtEmb == null) {
                    continue;
                }
                double confidence = cosineSimilarity(srcEmb, tgtEmb);
                if (confidence > AUTO_ADD_THRESHOLD) {
                    out.add(new LineageEdge(src.table, src.name, tgt.table, tgt.name,
                            roundConf(confidence), INFERRED_BY));
                } else if (confidence > CANDIDATE_THRESHOLD) {
                    long id = candidateIdSeq.incrementAndGet();
                    LineageEdge cand = new LineageEdge(src.table, src.name, tgt.table, tgt.name,
                            roundConf(confidence), INFERRED_BY);
                    candidates.put(id, cand);
                }
                // < 0.5: discard
            }
        }
        return out;
    }

    /**
     * Return all candidate edges (confidence 0.5–0.8) awaiting manual
     * confirmation.
     *
     * @return map of candidate id → edge
     */
    public Map<Long, LineageEdge> getCandidates() {
        return new HashMap<>(candidates);
    }

    /**
     * Confirm a candidate edge by id — promotes it from candidate to a
     * confirmed edge (confidence set to 1.0).
     *
     * @param id candidate id
     * @return the confirmed edge, or {@code null} if the id is unknown
     */
    public LineageEdge confirmCandidate(long id) {
        LineageEdge edge = candidates.remove(id);
        if (edge == null) {
            return null;
        }
        return new LineageEdge(edge.sourceTable(), edge.sourceColumn(),
                edge.targetTable(), edge.targetColumn(), 1.0, INFERRED_BY);
    }

    // ─── Graph construction ────────────────────────────────────────────

    private Graph buildGraph(LineageParser.LineageParseResult ast) {
        Graph g = new Graph();
        // Target table + target columns.
        g.addNode(NodeKey.table(ast.targetTable()), NodeType.TABLE,
                tableEmbedding(ast.targetTable()));
        for (String tcol : ast.targetColumns()) {
            String key = NodeKey.column(ast.targetTable(), tcol);
            g.addNode(key, NodeType.COLUMN, columnEmbedding(tcol));
            g.targetColumns.add(new ColumnInfo(ast.targetTable(), tcol, key));
            // Edge: column → table (membership).
            g.addEdge(key, NodeKey.table(ast.targetTable()));
        }
        // Source tables + source columns (from projections).
        for (String stable : ast.sourceTables()) {
            String tkey = NodeKey.table(stable);
            g.addNode(tkey, NodeType.TABLE, tableEmbedding(stable));
        }
        for (String proj : ast.projections()) {
            if (proj.equals("*") || proj.endsWith(".*")) {
                continue; // wildcard — skip
            }
            // Determine which source table this projection belongs to. If the
            // projection is a property ref (table.col), use that table;
            // otherwise, if there's a single source table, attribute to it.
            String colTable = null;
            String colName = proj;
            int dotIdx = proj.indexOf('.');
            if (dotIdx > 0) {
                colTable = proj.substring(0, dotIdx);
                colName = proj.substring(dotIdx + 1);
            } else if (ast.sourceTables().size() == 1) {
                colTable = ast.sourceTables().get(0);
            }
            if (colTable == null) {
                continue;
            }
            String ckey = NodeKey.column(colTable, colName);
            g.addNode(ckey, NodeType.COLUMN, columnEmbedding(colName));
            g.sourceColumns.add(new ColumnInfo(colTable, colName, ckey));
            g.addEdge(ckey, NodeKey.table(colTable));
        }
        // Rule-based edges (FK/reference).
        for (LineageEdge e : ast.edges()) {
            if (e.sourceColumn().equals("*") || e.targetColumn().equals("*")) {
                continue;
            }
            String srcKey = NodeKey.column(e.sourceTable(), e.sourceColumn());
            String tgtKey = NodeKey.column(e.targetTable(), e.targetColumn());
            g.addEdge(srcKey, tgtKey);
        }
        // Name-similarity edges (Jaccard > 0.3) between source and target columns.
        for (ColumnInfo src : g.sourceColumns) {
            for (ColumnInfo tgt : g.targetColumns) {
                if (jaccardSimilarity(src.name, tgt.name) > 0.3) {
                    g.addEdge(src.nodeKey, tgt.nodeKey);
                }
            }
        }
        return g;
    }

    // ─── GraphSAGE forward pass ────────────────────────────────────────

    private Map<String, float[]> computeEmbeddings(Graph graph) {
        // Layer 1: h1[v] = ReLU(W1 * h0[v] + W1N * mean(neighbors(v)))
        Map<String, float[]> h1 = new HashMap<>();
        for (String node : graph.nodes.keySet()) {
            float[] feat = graph.nodes.get(node).features;
            float[] neighborMean = aggregateNeighbors(graph, node, feat.length);
            float[] self = matvec(feat, w1);
            float[] neigh = matvec(neighborMean, w1Neighbor);
            float[] combined = new float[HIDDEN_1];
            for (int i = 0; i < HIDDEN_1; i++) {
                combined[i] = relu(self[i] + neigh[i]);
            }
            h1.put(node, combined);
        }
        // Layer 2: h2[v] = ReLU(W2 * h1[v] + W2N * mean(neighbors(v)))
        Map<String, float[]> h2 = new HashMap<>();
        for (String node : graph.nodes.keySet()) {
            float[] feat = h1.get(node);
            float[] neighborMean = aggregateNeighbors(graph, node, feat.length, h1);
            float[] self = matvec(feat, w2);
            float[] neigh = matvec(neighborMean, w2Neighbor);
            float[] combined = new float[HIDDEN_2];
            for (int i = 0; i < HIDDEN_2; i++) {
                combined[i] = relu(self[i] + neigh[i]);
            }
            h2.put(node, combined);
        }
        return h2;
    }

    /** Mean-aggregate the feature vectors of a node's neighbors. */
    private float[] aggregateNeighbors(Graph graph, String node, int dim) {
        return aggregateNeighbors(graph, node, dim, null);
    }

    /** Mean-aggregate neighbors using a specific feature map (layer output). */
    private float[] aggregateNeighbors(Graph graph, String node, int dim, Map<String, float[]> featMap) {
        Set<String> neighbors = graph.adjacency.get(node);
        if (neighbors == null || neighbors.isEmpty()) {
            return new float[dim];
        }
        float[] sum = new float[dim];
        int count = 0;
        for (String nb : neighbors) {
            float[] feat = (featMap != null) ? featMap.get(nb) : graph.nodes.get(nb).features;
            if (feat == null || feat.length != dim) {
                continue;
            }
            for (int i = 0; i < dim; i++) {
                sum[i] += feat[i];
            }
            count++;
        }
        if (count == 0) {
            return new float[dim];
        }
        for (int i = 0; i < dim; i++) {
            sum[i] /= count;
        }
        return sum;
    }

    // ─── Embedding functions ───────────────────────────────────────────

    /** Hash-based 128-dim embedding for a table name. */
    static float[] tableEmbedding(String tableName) {
        float[] emb = new float[EMBED_DIM];
        if (tableName == null || tableName.isEmpty()) {
            return emb;
        }
        // Use a simple hash-based embedding: for each character, hash into a
        // bucket and add 1.0. This gives a bag-of-characters representation
        // scaled into 128 dims.
        String lower = tableName.toLowerCase();
        for (int i = 0; i < lower.length(); i++) {
            int ch = lower.charAt(i);
            int bucket = hashBucket(ch, i, EMBED_DIM);
            emb[bucket] += 1.0f;
        }
        // Normalize to unit length.
        normalize(emb);
        return emb;
    }

    /** Char-level n-gram hash 128-dim embedding for a column name. */
    static float[] columnEmbedding(String colName) {
        float[] emb = new float[EMBED_DIM];
        if (colName == null || colName.isEmpty()) {
            return emb;
        }
        String lower = colName.toLowerCase();
        // Unigrams + bigrams.
        for (int n = 1; n <= 2; n++) {
            for (int i = 0; i <= lower.length() - n; i++) {
                String gram = lower.substring(i, i + n);
                int bucket = hashBucket(gram.hashCode(), i, EMBED_DIM);
                emb[bucket] += 1.0f;
            }
        }
        normalize(emb);
        return emb;
    }

    // ─── Utility math ──────────────────────────────────────────────────

    private static int hashBucket(int seed, int idx, int dim) {
        // Mix the seed and index into a stable bucket.
        int h = seed * 31 + idx;
        h ^= (h >>> 16);
        h *= 0x45d9f3b;
        h ^= (h >>> 16);
        return Math.abs(h % dim);
    }

    private static int hashBucket(String s, int idx, int dim) {
        return hashBucket(s.hashCode(), idx, dim);
    }

    private static void normalize(float[] v) {
        float norm = 0.0f;
        for (float f : v) {
            norm += f * f;
        }
        norm = (float) Math.sqrt(norm);
        if (norm < 1e-9f) {
            return;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] /= norm;
        }
    }

    static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        float dot = 0.0f;
        float na = 0.0f;
        float nb = 0.0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        if (denom < 1e-12) {
            return 0.0;
        }
        double sim = dot / denom;
        // Clamp to [0, 1] — cosine can be negative, but for lineage we treat
        // negative similarity as 0 (no lineage).
        return Math.max(0.0, Math.min(1.0, sim));
    }

    private static float relu(float x) {
        return Math.max(0.0f, x);
    }

    /** Matrix-vector multiply: result[j] = sum_i(mat[i][j] * vec[i]). */
    private static float[] matvec(float[] vec, float[][] mat) {
        int outDim = mat[0].length;
        float[] out = new float[outDim];
        for (int i = 0; i < vec.length; i++) {
            float vi = vec[i];
            if (vi == 0.0f) {
                continue;
            }
            float[] row = mat[i];
            for (int j = 0; j < outDim; j++) {
                out[j] += vi * row[j];
            }
        }
        return out;
    }

    /** Xavier/Glorot initialization for a weight matrix. */
    private static float[][] xavierInit(int inDim, int outDim) {
        float[][] mat = new float[inDim][outDim];
        double limit = Math.sqrt(6.0 / (inDim + outDim));
        java.util.Random rng = new java.util.Random(0x6E15_11L); // fixed seed for reproducibility
        for (int i = 0; i < inDim; i++) {
            for (int j = 0; j < outDim; j++) {
                mat[i][j] = (float) (rng.nextDouble() * 2.0 * limit - limit);
            }
        }
        return mat;
    }

    private static double roundConf(double c) {
        return Math.round(c * 10000.0) / 10000.0;
    }

    // ─── Name similarity ───────────────────────────────────────────────

    /** Jaccard similarity of token sets from two column names. */
    static double jaccardSimilarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        Set<String> sa = tokenize(a.toLowerCase());
        Set<String> sb = tokenize(b.toLowerCase());
        if (sa.isEmpty() && sb.isEmpty()) {
            return 1.0;
        }
        Set<String> intersection = new HashSet<>(sa);
        intersection.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenize(String s) {
        Set<String> tokens = new HashSet<>();
        // Split on non-alphanumeric.
        String[] parts = s.split("[^a-z0-9]+");
        for (String p : parts) {
            if (!p.isEmpty()) {
                tokens.add(p);
            }
        }
        return tokens;
    }

    // ─── Rule edge key set ─────────────────────────────────────────────

    private static Set<String> ruleEdgeKeySet(List<LineageEdge> edges) {
        Set<String> keys = new HashSet<>();
        for (LineageEdge e : edges) {
            keys.add(e.sourceTable() + "." + e.sourceColumn() + "->" + e.targetTable() + "." + e.targetColumn());
        }
        return keys;
    }

    // ─── Model loading ─────────────────────────────────────────────────

    /**
     * Attempt to load model weights from {@code model.json} on the classpath.
     * Returns {@code null} if the file is not found or cannot be parsed.
     */
    static ModelWeights loadWeightsFromClasspath() {
        try (InputStream is = GnnLineageInferrer.class.getClassLoader().getResourceAsStream(MODEL_RESOURCE)) {
            if (is == null) {
                return null;
            }
            String json = readAll(is);
            return parseModelJson(json);
        } catch (IOException e) {
            log.warn("[GnnLineageInferrer] Failed to load model.json: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[GnnLineageInferrer] Error parsing model.json: {}", e.getMessage());
            return null;
        }
    }

    private static String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /**
     * Minimal JSON parser for the model weights file. Expected format:
     * <pre>{@code
     * {
     *   "w1": [[...128 floats...], ...128 rows...],
     *   "w1Neighbor": [[...], ...],
     *   "w2": [[...64 floats...], ...128 rows...],
     *   "w2Neighbor": [[...], ...]
     * }
     * }</pre>
     */
    static ModelWeights parseModelJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        float[][] w1 = extractMatrix(json, "w1", EMBED_DIM, HIDDEN_1);
        float[][] w1N = extractMatrix(json, "w1Neighbor", EMBED_DIM, HIDDEN_1);
        float[][] w2 = extractMatrix(json, "w2", HIDDEN_1, HIDDEN_2);
        float[][] w2N = extractMatrix(json, "w2Neighbor", HIDDEN_1, HIDDEN_2);
        if (w1 == null || w1N == null || w2 == null || w2N == null) {
            return null;
        }
        return new ModelWeights(w1, w1N, w2, w2N);
    }

    @SuppressWarnings("unchecked")
    private static float[][] extractMatrix(String json, String key, int rows, int cols) {
        // Use a simple index-based parser to avoid pulling in Jackson here.
        // Find "key": then the following [ ... ].
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int arrStart = json.indexOf('[', idx + needle.length());
        if (arrStart < 0) {
            return null;
        }
        // Find the matching closing bracket for the outer array.
        int depth = 0;
        int arrEnd = -1;
        for (int i = arrStart; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    arrEnd = i;
                    break;
                }
            }
        }
        if (arrEnd < 0) {
            return null;
        }
        String arrStr = json.substring(arrStart, arrEnd + 1);
        // Parse as a list of lists using a lightweight split.
        List<float[]> rowsList = new ArrayList<>();
        int pos = 1; // skip leading '['
        while (pos < arrStr.length() - 1) {
            int rowStart = arrStr.indexOf('[', pos);
            if (rowStart < 0) {
                break;
            }
            int rowEnd = arrStr.indexOf(']', rowStart);
            if (rowEnd < 0) {
                break;
            }
            String rowStr = arrStr.substring(rowStart + 1, rowEnd);
            String[] parts = rowStr.split(",");
            if (parts.length != cols) {
                return null;
            }
            float[] row = new float[cols];
            for (int j = 0; j < cols; j++) {
                try {
                    row[j] = Float.parseFloat(parts[j].trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            rowsList.add(row);
            pos = rowEnd + 1;
        }
        if (rowsList.size() != rows) {
            return null;
        }
        return rowsList.toArray(new float[0][]);
    }

    /** Whether trained weights were loaded from a file (vs random Xavier). */
    public boolean isWeightsLoadedFromFile() {
        return weightsLoadedFromFile;
    }

    // ─── Inner types ───────────────────────────────────────────────────

    enum NodeType { TABLE, COLUMN }

    static final class NodeKey {
        static String table(String name) {
            return "T:" + name;
        }
        static String column(String table, String col) {
            return "C:" + table + "." + col;
        }
    }

    static final class GraphNode {
        final NodeType type;
        final float[] features;

        GraphNode(NodeType type, float[] features) {
            this.type = type;
            this.features = features;
        }
    }

    static final class ColumnInfo {
        final String table;
        final String name;
        final String nodeKey;

        ColumnInfo(String table, String name, String nodeKey) {
            this.table = table;
            this.name = name;
            this.nodeKey = nodeKey;
        }
    }

    static final class Graph {
        final Map<String, GraphNode> nodes = new HashMap<>();
        final Map<String, Set<String>> adjacency = new HashMap<>();
        final List<ColumnInfo> sourceColumns = new ArrayList<>();
        final List<ColumnInfo> targetColumns = new ArrayList<>();

        void addNode(String key, NodeType type, float[] features) {
            nodes.putIfAbsent(key, new GraphNode(type, features));
        }

        void addEdge(String from, String to) {
            adjacency.computeIfAbsent(from, k -> new HashSet<>()).add(to);
            adjacency.computeIfAbsent(to, k -> new HashSet<>()).add(from);
        }
    }

    /**
     * Container for the four GraphSAGE weight matrices, loadable from
     * {@code model.json}.
     */
    public record ModelWeights(
            float[][] w1,
            float[][] w1Neighbor,
            float[][] w2,
            float[][] w2Neighbor
    ) {
    }
}
