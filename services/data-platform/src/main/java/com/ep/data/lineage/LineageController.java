package com.ep.data.lineage;

import java.util.List;
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
 */
@RestController
@RequestMapping("/api/v1/lineage")
@Tag(name = "血缘管理", description = "字段级血缘查询与SQL解析")
public class LineageController {

    private static final Logger log = LoggerFactory.getLogger(LineageController.class);

    private final LineageParser parser;
    private final LineageGraph graph;

    public LineageController(LineageParser parser, LineageGraph graph) {
        this.parser = parser;
        this.graph = graph;
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

    /** Payload for {@code POST /api/v1/lineage/parse}. */
    public record ParseCmd(String sql) {
    }
}
