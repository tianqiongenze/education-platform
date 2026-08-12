package com.ep.data.lineage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link LineageParser}. No Spring context, no IO.
 */
class LineageParserTest {

    private final LineageParser parser = new LineageParser();

    @Test
    void test_simple_insert_select_with_explicit_columns() {
        String sql = "INSERT INTO target (c1, c2) SELECT a, b FROM source";
        List<LineageEdge> edges = parser.parseSql(sql);

        assertEquals(2, edges.size());
        LineageEdge e0 = edges.get(0);
        assertEquals("source", e0.sourceTable());
        assertEquals("a", e0.sourceColumn());
        assertEquals("target", e0.targetTable());
        assertEquals("c1", e0.targetColumn());
        assertEquals(1.0, e0.confidence());
        assertEquals("rule", e0.inferredBy());

        LineageEdge e1 = edges.get(1);
        assertEquals("b", e1.sourceColumn());
        assertEquals("c2", e1.targetColumn());
    }

    @Test
    void test_insert_select_wildcard() {
        String sql = "INSERT INTO target (c1, c2) SELECT * FROM source";
        List<LineageEdge> edges = parser.parseSql(sql);

        assertEquals(1, edges.size());
        LineageEdge e = edges.get(0);
        assertEquals("*", e.sourceColumn());
        assertEquals("*", e.targetColumn());
        assertEquals("source", e.sourceTable());
        assertEquals("target", e.targetTable());
    }

    @Test
    void test_insert_select_with_join() {
        String sql = "INSERT INTO target (c1, c2) SELECT a, b FROM s1 JOIN s2 ON s1.id = s2.id";
        List<LineageEdge> edges = parser.parseSql(sql);

        // 2 target columns × 2 source tables = 4 edges
        assertEquals(4, edges.size());
        long s1Edges = edges.stream().filter(e -> e.sourceTable().equals("s1")).count();
        long s2Edges = edges.stream().filter(e -> e.sourceTable().equals("s2")).count();
        assertEquals(2, s1Edges);
        assertEquals(2, s2Edges);
    }

    @Test
    void test_insert_select_without_target_column_list_positional() {
        String sql = "INSERT INTO target SELECT a, b FROM source";
        List<LineageEdge> edges = parser.parseSql(sql);

        assertEquals(2, edges.size());
        // No target column list → targetColumn is "*"
        assertTrue(edges.stream().allMatch(e -> "*".equals(e.targetColumn())));
        assertEquals("a", edges.get(0).sourceColumn());
        assertEquals("b", edges.get(1).sourceColumn());
    }

    @Test
    void test_non_insert_sql_returns_empty() {
        assertTrue(parser.parseSql("SELECT * FROM t").isEmpty());
        assertTrue(parser.parseSql("UPDATE t SET a = 1").isEmpty());
        assertTrue(parser.parseSql("DELETE FROM t").isEmpty());
    }

    @Test
    void test_insert_without_select_returns_empty() {
        assertTrue(parser.parseSql("INSERT INTO target (c1) VALUES (1)").isEmpty());
    }

    @Test
    void test_null_or_empty_sql_returns_empty() {
        assertTrue(parser.parseSql(null).isEmpty());
        assertTrue(parser.parseSql("").isEmpty());
        assertTrue(parser.parseSql("   ").isEmpty());
    }

    @Test
    void test_backtick_quoted_identifiers() {
        String sql = "INSERT INTO `target` (`c1`, `c2`) SELECT `a`, `b` FROM `source`";
        List<LineageEdge> edges = parser.parseSql(sql);

        assertEquals(2, edges.size());
        assertEquals("target", edges.get(0).targetTable());
        assertEquals("source", edges.get(0).sourceTable());
        assertEquals("c1", edges.get(0).targetColumn());
        assertEquals("a", edges.get(0).sourceColumn());
    }

    @Test
    void test_cte_with_insert() {
        String sql = "WITH cte AS (SELECT id, name FROM raw) "
                + "INSERT INTO target (c1, c2) SELECT id, name FROM cte";
        List<LineageEdge> edges = parser.parseSql(sql);

        assertEquals(2, edges.size());
        assertEquals("cte", edges.get(0).sourceTable());
        assertEquals("target", edges.get(0).targetTable());
        assertEquals("c1", edges.get(0).targetColumn());
    }

    @Test
    void test_insert_select_star_dot_wildcard() {
        String sql = "INSERT INTO target (c1, c2) SELECT s.* FROM source s";
        List<LineageEdge> edges = parser.parseSql(sql);

        assertEquals(1, edges.size());
        assertEquals("*", edges.get(0).targetColumn());
    }
}
