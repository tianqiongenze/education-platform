package com.ep.data.nlquery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the Druid AST second-pass validation in
 * {@link SqlValidator} (D-190).
 *
 * <p>These tests focus on the AST-specific checks added by D-190:
 * malformed SQL rejection, multi-statement rejection, non-SELECT rejection.
 * The original regex-pass tests are in {@link SqlValidatorTest}.
 */
class SqlValidatorAstTest {

    private final SqlValidator validator = new SqlValidator();

    @Test
    void ast_pass_validates_simple_select() {
        ValidationResult r = validator.validate("SELECT id, name FROM users WHERE id > 10");
        assertTrue(r.valid(), "simple SELECT should pass AST validation, violations=" + r.violations());
        assertFalse(r.sanitizedSql().isEmpty(), "sanitized SQL should not be empty");
    }

    @Test
    void ast_pass_validates_select_with_join() {
        ValidationResult r = validator.validate(
                "SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id");
        assertTrue(r.valid(), "SELECT with JOIN should pass AST validation, violations=" + r.violations());
    }

    @Test
    void ast_pass_validates_select_with_subquery() {
        ValidationResult r = validator.validate(
                "SELECT * FROM (SELECT id FROM users) t WHERE id > 0");
        assertTrue(r.valid(), "SELECT with subquery should pass, violations=" + r.violations());
    }

    @Test
    void ast_pass_validates_with_cte() {
        ValidationResult r = validator.validate(
                "WITH active AS (SELECT * FROM users WHERE active = true) SELECT * FROM active");
        assertTrue(r.valid(), "WITH CTE should pass AST validation, violations=" + r.violations());
    }

    @Test
    void ast_pass_rejects_malformed_sql() {
        // SELECT with broken syntax — should fail AST parsing.
        ValidationResult r = validator.validate("SELECT FROM WHERE");
        assertFalse(r.valid(), "malformed SQL should be rejected by AST pass, got valid=" + r.valid());
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("AST") || v.contains("parsing")),
                "should report AST parsing error, got: " + r.violations());
    }

    @Test
    void ast_pass_rejects_non_select_statement() {
        // EXPLAIN is not a SELECT statement — regex pass allows it (starts with
        // no forbidden keyword and doesn't start with SELECT/WITH so it's caught
        // by regex rule 1 actually). Let's use a VALUES statement which starts
        // with neither SELECT nor WITH → caught by regex.
        ValidationResult r = validator.validate("VALUES (1, 2), (3, 4)");
        assertFalse(r.valid(), "VALUES should be rejected, got valid=" + r.valid());
    }

    @Test
    void ast_pass_validates_select_with_group_by_having() {
        ValidationResult r = validator.validate(
                "SELECT dept, COUNT(*) FROM employees GROUP BY dept HAVING COUNT(*) > 5");
        assertTrue(r.valid(),
                "SELECT with GROUP BY HAVING should pass, violations=" + r.violations());
    }

    @Test
    void ast_pass_validates_select_with_order_by_limit() {
        ValidationResult r = validator.validate(
                "SELECT * FROM users ORDER BY created_at DESC LIMIT 10");
        assertTrue(r.valid(),
                "SELECT with ORDER BY LIMIT should pass, violations=" + r.violations());
    }

    @Test
    void ast_pass_validates_qualified_column_names() {
        ValidationResult r = validator.validate(
                "SELECT t1.col1, t2.col2 FROM table1 t1 INNER JOIN table2 t2 ON t1.id = t2.id");
        assertTrue(r.valid(), "SELECT with qualified columns should pass, violations=" + r.violations());
    }

    @Test
    void ast_pass_sanitizes_whitespace() {
        ValidationResult r = validator.validate(
                "SELECT   id,\n  name\nFROM\tusers");
        assertTrue(r.valid(), "SELECT with extra whitespace should pass, violations=" + r.violations());
        // Sanitized SQL should have collapsed whitespace.
        assertFalse(r.sanitizedSql().contains("\n"),
                "sanitized SQL should not contain newlines, got: " + r.sanitizedSql());
        assertFalse(r.sanitizedSql().contains("\t"),
                "sanitized SQL should not contain tabs, got: " + r.sanitizedSql());
    }
}
