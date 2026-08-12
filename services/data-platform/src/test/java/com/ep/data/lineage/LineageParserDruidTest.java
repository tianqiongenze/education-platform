package com.ep.data.lineage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-188: Tests for the Druid AST-based {@link LineageParser}.
 *
 * <p>These tests exercise the new {@code parseSqlAst} entry point that
 * returns a {@link LineageParser.LineageParseResult} carrying structural
 * details (source tables, target columns, projections, wildcard flag) in
 * addition to the rule-based edges. They also cover AST-only shapes that the
 * old regex parser could not handle (subqueries in FROM, UNION arms).
 *
 * <p>Pure unit tests — no Spring context, no IO. Instantiated with
 * {@code new LineageParser()}.
 */
class LineageParserDruidTest {

    private final LineageParser parser = new LineageParser();

    // ─── parseSqlAst: structural details ───────────────────────────────

    @Test
    void ast_simple_insert_select_returns_full_result() {
        String sql = "INSERT INTO target (c1, c2) SELECT a, b FROM source";
        LineageParser.LineageParseResult ast = parser.parseSqlAst(sql);

        assertNotNull(ast);
        assertEquals("target", ast.targetTable());
        assertEquals(List.of("c1", "c2"), ast.targetColumns());
        assertEquals(List.of("source"), ast.sourceTables());
        assertEquals(List.of("a", "b"), ast.projections());
        assertFalse(ast.wildcard());
        assertEquals(2, ast.edges().size());
    }

    @Test
    void ast_wildcard_projection_sets_flag() {
        String sql = "INSERT INTO target (c1, c2) SELECT * FROM source";
        LineageParser.LineageParseResult ast = parser.parseSqlAst(sql);

        assertNotNull(ast);
        assertTrue(ast.wildcard(), "wildcard flag should be true for SELECT *");
        assertEquals(1, ast.edges().size(), "one wildcard edge per source table");
        LineageEdge e = ast.edges().get(0);
        assertEquals("*", e.sourceColumn());
        assertEquals("*", e.targetColumn());
    }

    @Test
    void ast_star_dot_wildcard_sets_flag() {
        String sql = "INSERT INTO target (c1, c2) SELECT s.* FROM source s";
        LineageParser.LineageParseResult ast = parser.parseSqlAst(sql);

        assertNotNull(ast);
        assertTrue(ast.wildcard(), "wildcard flag should be true for s.*");
    }

    @Test
    void ast_join_collects_multiple_source_tables() {
        String sql = "INSERT INTO target (c1, c2) SELECT a, b FROM s1 JOIN s2 ON s1.id = s2.id";
        LineageParser.LineageParseResult ast = parser.parseSqlAst(sql);

        assertNotNull(ast);
        assertEquals(2, ast.sourceTables().size());
        assertTrue(ast.sourceTables().contains("s1"));
        assertTrue(ast.sourceTables().contains("s2"));
        // 2 target cols × 2 source tables = 4 edges
        assertEquals(4, ast.edges().size());
    }

    @Test
    void ast_subquery_in_from_collects_inner_tables() {
        String sql = "INSERT INTO target (c1) SELECT a FROM (SELECT a FROM inner_t) sub";
        LineageParser.LineageParseResult ast = parser.parseSqlAst(sql);

        assertNotNull(ast);
        assertTrue(ast.sourceTables().contains("inner_t"),
                "sourceTables should contain inner_t, got " + ast.sourceTables());
    }

    @Test
    void ast_union_collects_tables_from_all_arms() {
        String sql = "INSERT INTO target (c1) SELECT a FROM s1 UNION SELECT b FROM s2";
        LineageParser.LineageParseResult ast = parser.parseSqlAst(sql);

        assertNotNull(ast);
        assertTrue(ast.sourceTables().contains("s1"),
                "sourceTables should contain s1, got " + ast.sourceTables());
        assertTrue(ast.sourceTables().contains("s2"),
                "sourceTables should contain s2, got " + ast.sourceTables());
        // Projections come from the first UNION arm only.
        assertEquals(List.of("a"), ast.projections());
    }

    @Test
    void ast_cte_with_insert() {
        String sql = "WITH cte AS (SELECT id, name FROM raw) "
                + "INSERT INTO target (c1, c2) SELECT id, name FROM cte";
        LineageParser.LineageParseResult ast = parser.parseSqlAst(sql);

        assertNotNull(ast);
        assertEquals("target", ast.targetTable());
        assertTrue(ast.sourceTables().contains("cte"));
        assertEquals(2, ast.edges().size());
    }

    @Test
    void ast_no_target_column_list() {
        String sql = "INSERT INTO target SELECT a, b FROM source";
        LineageParser.LineageParseResult ast = parser.parseSqlAst(sql);

        assertNotNull(ast);
        assertTrue(ast.targetColumns().isEmpty());
        assertEquals(2, ast.edges().size());
        // Without target list, targetColumn is "*"
        assertTrue(ast.edges().stream().allMatch(e -> "*".equals(e.targetColumn())));
    }

    @Test
    void ast_backtick_quoted_identifiers_stripped() {
        String sql = "INSERT INTO `target` (`c1`, `c2`) SELECT `a`, `b` FROM `source`";
        LineageParser.LineageParseResult ast = parser.parseSqlAst(sql);

        assertNotNull(ast);
        assertEquals("target", ast.targetTable());
        assertEquals("source", ast.sourceTables().get(0));
        assertEquals(List.of("c1", "c2"), ast.targetColumns());
    }

    @Test
    void ast_property_ref_projection_strips_table_prefix() {
        String sql = "INSERT INTO target (c1) SELECT t.a FROM source t";
        LineageParser.LineageParseResult ast = parser.parseSqlAst(sql);

        assertNotNull(ast);
        // t.a → projection "a" (property ref → simple name)
        assertEquals("a", ast.projections().get(0));
    }

    // ─── parseSqlAst: null / non-INSERT cases ──────────────────────────

    @Test
    void ast_null_or_blank_returns_null() {
        assertNull(parser.parseSqlAst(null));
        assertNull(parser.parseSqlAst(""));
        assertNull(parser.parseSqlAst("   "));
    }

    @Test
    void ast_non_insert_returns_null() {
        assertNull(parser.parseSqlAst("SELECT * FROM t"));
        assertNull(parser.parseSqlAst("UPDATE t SET a = 1"));
        assertNull(parser.parseSqlAst("DELETE FROM t"));
    }

    @Test
    void ast_insert_without_select_returns_null() {
        assertNull(parser.parseSqlAst("INSERT INTO target (c1) VALUES (1)"));
    }

    @Test
    void ast_unparseable_sql_returns_null() {
        assertNull(parser.parseSqlAst("THIS IS NOT SQL @@@"));
    }

    // ─── Backward compatibility: parseSql still works ──────────────────

    @Test
    void parseSql_backward_compatible_returns_edges_only() {
        String sql = "INSERT INTO target (c1, c2) SELECT a, b FROM source";
        List<LineageEdge> edges = parser.parseSql(sql);

        assertEquals(2, edges.size());
        // All rule-based edges have confidence=1.0 and inferredBy="rule"
        for (LineageEdge e : edges) {
            assertEquals(1.0, e.confidence(), 1e-9);
            assertEquals("rule", e.inferredBy());
        }
    }

    @Test
    void parseSql_empty_input_returns_empty_list() {
        assertTrue(parser.parseSql(null).isEmpty());
        assertTrue(parser.parseSql("").isEmpty());
    }
}
