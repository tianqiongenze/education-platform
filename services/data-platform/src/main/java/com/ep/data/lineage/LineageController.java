package com.ep.data.lineage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for column-level lineage query and SQL parsing (§12.1.2).
 *
 * <p>Exposes BFS upstream/downstream traversal over an in-memory
 * {@link LineageGraph}, plus a SQL parsing endpoint that adds parsed edges
 * to the graph. The in-memory graph is the dev/CI fallback (same pattern as
 * MetadataController); production deployments load edges from the Iceberg
 * {@code meta.column_lineage} table.
 *
 * <p>D-188 adds GNN-based inference endpoints: {@code /gnn-infer} to trigger
 * GraphSAGE inference on parsed SQL, {@code /candidates} to list edges with
 * confidence 0.5–0.8 awaiting manual confirmation, and
 * {@code /candidates/{id}/confirm} to confirm a candidate.
 */
@RestController
@RequestMapping("/api/v1/lineage")
@Tag(name = "血缘管理", description = "字段级血缘查询与SQL解析")
public class LineageController {

    private static final Logger log = LoggerFactory.getLogger(LineageController.class);

    private final LineageParser parser;
    private final LineageGraph graph;
    private final GnnLineageInferrer gnnInferrer;

    /** Original constructor (backward compatible — GNN inferrer auto-wired). */
    public LineageController(LineageParser parser, LineageGraph graph, GnnLineageInferrer gnnInferrer) {
        this.parser = parser;
        this.graph = graph;
        this.gnnInferrer = gnnInferrer;
    }

    /**
     * Upstream lineage (BFS) for a table.
     *
     * @param table target table name
     * @param depth BFS depth (default 3)
     * @return set of upstream table names
     */
    @GetMapping("/tables/{table}/upstream")
    public ResponseEntity<Set<String>> upstream(
            @PathVariable String table,
            @RequestParam(name = "depth", defaultValue = "3") int depth) {
        return ResponseEntity.ok(graph.upstream(table, depth));
    }

    /**
     * Downstream lineage (BFS) for a table.
     *
     * @param table source table name
     * @param depth BFS depth (default 3)
     * @return set of downstream table names
     */
    @GetMapping("/tables/{table}/downstream")
    public ResponseEntity<Set<String>> downstream(
            @PathVariable String table,
            @RequestParam(name = "depth", defaultValue = "3") int depth) {
        return ResponseEntity.ok(graph.downstream(table, depth));
    }

    /**
     * Parse a SQL string into lineage edges and add them to the graph.
     *
     * @param cmd payload containing the SQL to parse
     * @return parsed lineage edges
     */
    @PostMapping("/parse")
    public ResponseEntity<List<LineageEdge>> parse(@RequestBody ParseCmd cmd) {
        if (cmd == null || cmd.sql() == null || cmd.sql().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        List<LineageEdge> edges = parser.parseSql(cmd.sql());
        graph.addEdges(edges);
        log.info("[LineageController] Parsed SQL — {} edges added to graph", edges.size());
        return ResponseEntity.ok(edges);
    }

    /**
     * Trigger GNN-based inference on parsed SQL (D-188).
     *
     * <p>Parses the SQL with the rule-based parser, then runs the GraphSAGE
     * inferrer to discover additional column-level edges not captured by
     * rules. Auto-added edges (confidence &gt; 0.8) are added to the graph
     * and persisted.
     *
     * @param cmd payload containing the SQL to infer lineage from
     * @return GNN-inferred edges with {@code inferredBy="gnn"}
     */
    @PostMapping("/gnn-infer")
    public ResponseEntity<List<LineageEdge>> gnnInfer(@RequestBody ParseCmd cmd) {
        if (cmd == null || cmd.sql() == null || cmd.sql().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        LineageParser.LineageParseResult ast = parser.parseSqlAst(cmd.sql());
        if (ast == null) {
            return ResponseEntity.ok(List.of());
        }
        // Add rule-based edges to the graph first.
        graph.addEdges(ast.edges());
        // Run GNN inference.
        List<LineageEdge> gnnEdges = gnnInferrer.infer(ast);
        graph.addEdges(gnnEdges);
        graph.persistEdges(gnnEdges);
        log.info("[LineageController] GNN inference — {} auto-added edges (inferredBy=gnn)", gnnEdges.size());
        return ResponseEntity.ok(gnnEdges);
    }

    /**
     * Get candidate edges (confidence 0.5–0.8) awaiting manual confirmation
     * (D-188).
     *
     * @return map of candidate id → edge
     */
    @GetMapping("/candidates")
    public ResponseEntity<Map<Long, LineageEdge>> candidates() {
        return ResponseEntity.ok(gnnInferrer.getCandidates());
    }

    /**
     * Confirm a candidate edge by id — promotes it to a confirmed edge
     * (D-188).
     *
     * @param id candidate id
     * @return the confirmed edge (confidence set to 1.0), or 404 if unknown
     */
    @PostMapping("/candidates/{id}/confirm")
    public ResponseEntity<LineageEdge> confirmCandidate(@PathVariable long id) {
        LineageEdge confirmed = gnnInferrer.confirmCandidate(id);
        if (confirmed == null) {
            return ResponseEntity.notFound().build();
        }
        graph.addEdges(List.of(confirmed));
        graph.persistEdges(List.of(confirmed));
        log.info("[LineageController] Confirmed candidate {} — edge added to graph", id);
        return ResponseEntity.ok(confirmed);
    }

    /** Payload for {@code POST /api/v1/lineage/parse} and {@code /gnn-infer}. */
    public record ParseCmd(String sql) {
    }
}
