package com.ep.data.nlquery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link SqlValidator}.
 *
 * <p>No Spring context — the validator is a plain POJO instantiated with
 * {@code new}. Each assertion includes a descriptive failure message with
 * the actual value.
 */
class SqlValidatorTest {

    private final SqlValidator validator = new SqlValidator();

    @Test
    void test_valid_select_passes() {
        ValidationResult r = validator.validate("SELECT * FROM users");
        assertTrue(r.valid(), "SELECT * FROM users should be valid, violations=" + r.violations());
        assertEquals("SELECT * FROM users", r.sanitizedSql(),
                "sanitizedSql should match input, got " + r.sanitizedSql());
        assertTrue(r.violations().isEmpty(), "violations should be empty, got " + r.violations());
    }

    @Test
    void test_valid_with_cte_passes() {
        ValidationResult r = validator.validate("WITH t AS (SELECT 1) SELECT * FROM t");
        assertTrue(r.valid(), "WITH ... SELECT should be valid, violations=" + r.violations());
        assertFalse(r.sanitizedSql().isEmpty(), "sanitizedSql should not be empty");
    }

    @Test
    void test_insert_rejected() {
        ValidationResult r = validator.validate("INSERT INTO users VALUES(1)");
        assertFalse(r.valid(), "INSERT should be rejected, got valid=" + r.valid());
        assertFalse(r.violations().isEmpty(), "violations should not be empty, got " + r.violations());
        assertEquals("", r.sanitizedSql(), "sanitizedSql should be empty on failure");
    }

    @Test
    void test_drop_rejected() {
        ValidationResult r = validator.validate("DROP TABLE users");
        assertFalse(r.valid(), "DROP should be rejected, got valid=" + r.valid());
        assertFalse(r.violations().isEmpty(), "violations should not be empty, got " + r.violations());
    }

    @Test
    void test_semicolon_rejected() {
        ValidationResult r = validator.validate("SELECT * FROM users;");
        assertFalse(r.valid(), "trailing semicolon should be rejected, got valid=" + r.valid());
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("semicolon")),
                "should report semicolon violation, got " + r.violations());
    }

    @Test
    void test_line_comment_rejected() {
        ValidationResult r = validator.validate("SELECT * FROM users -- comment");
        assertFalse(r.valid(), "line comment should be rejected, got valid=" + r.valid());
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("line comment")),
                "should report line comment violation, got " + r.violations());
    }

    @Test
    void test_block_comment_rejected() {
        ValidationResult r = validator.validate("SELECT /* comment */ * FROM users");
        assertFalse(r.valid(), "block comment should be rejected, got valid=" + r.valid());
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("block comment")),
                "should report block comment violation, got " + r.violations());
    }

    @Test
    void test_case_insensitive_select() {
        ValidationResult r = validator.validate("select * from users");
        assertTrue(r.valid(), "lowercase select should be valid, violations=" + r.violations());
        assertEquals("select * from users", r.sanitizedSql(),
                "sanitizedSql should preserve case, got " + r.sanitizedSql());
    }

    @Test
    void test_empty_sql_rejected() {
        ValidationResult r = validator.validate("");
        assertFalse(r.valid(), "empty SQL should be rejected, got valid=" + r.valid());
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("empty")),
                "should report empty violation, got " + r.violations());
        assertEquals("", r.sanitizedSql(), "sanitizedSql should be empty");
    }

    @Test
    void test_null_sql_rejected() {
        ValidationResult r = validator.validate(null);
        assertFalse(r.valid(), "null SQL should be rejected, got valid=" + r.valid());
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("empty")),
                "should report empty violation for null, got " + r.violations());
    }

    @Test
    void test_complex_nested_select_passes() {
        ValidationResult r = validator.validate(
                "SELECT a, (SELECT COUNT(*) FROM t2) FROM t1 WHERE x IN (SELECT y FROM t3)");
        assertTrue(r.valid(), "complex nested SELECT should be valid, violations=" + r.violations());
        assertFalse(r.sanitizedSql().isEmpty(), "sanitizedSql should not be empty");
    }

    @Test
    void test_update_rejected() {
        ValidationResult r = validator.validate("UPDATE users SET name='x'");
        assertFalse(r.valid(), "UPDATE should be rejected, got valid=" + r.valid());
        assertFalse(r.violations().isEmpty(), "violations should not be empty, got " + r.violations());
    }

    @Test
    void test_create_rejected() {
        ValidationResult r = validator.validate("CREATE TABLE foo (id INT)");
        assertFalse(r.valid(), "CREATE should be rejected, got valid=" + r.valid());
        assertFalse(r.violations().isEmpty(), "violations should not be empty, got " + r.violations());
    }

    @Test
    void test_whitespace_only_rejected() {
        ValidationResult r = validator.validate("   ");
        assertFalse(r.valid(), "whitespace-only SQL should be rejected, got valid=" + r.valid());
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("empty")),
                "should report empty violation for whitespace, got " + r.violations());
    }

    @Test
    void test_multiple_violations_collected() {
        // INSERT + semicolon → two violations
        ValidationResult r = validator.validate("INSERT INTO t VALUES(1);");
        assertFalse(r.valid(), "should be rejected, got valid=" + r.valid());
        assertTrue(r.violations().size() >= 2,
                "should collect multiple violations, got " + r.violations().size() + ": " + r.violations());
    }
}
