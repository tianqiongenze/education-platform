package com.ep.data.nlquery;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.parser.ParserException;

/**
 * SQL white-list validator for the NL Query Text-to-SQL module
 * (ADR-D08, §12.4.3 security), upgraded with Druid AST second-pass
 * validation (D-190).
 *
 * <p><b>Two-pass validation:</b>
 * <ol>
 *   <li><b>Regex pass (fast pre-check):</b> Same white-list rules as before —
 *       must start with SELECT/WITH, no forbidden DML/DDL keywords, no
 *       semicolons, no comments. This catches the vast majority of injection
 *       attempts cheaply.</li>
 *   <li><b>Druid AST pass (D-190):</b> Parses the SQL into a Druid AST using
 *       {@code DbType.trino} and verifies that:
 *       <ul>
 *         <li>The SQL parses successfully (rejects malformed SQL).</li>
 *         <li>Exactly one statement is produced (no multi-statement injection
 *             that slipped past the semicolon check via encoding tricks).</li>
 *         <li>The statement is a {@link SQLSelectStatement} (SELECT-only).</li>
 *       </ul>
 * </ol>
 *
 * <p>If the Druid parser is unavailable or throws an unexpected error, the
 * validator falls back to regex-only validation (graceful degradation).
 *
 * <p>This class is pure (no Spring/IO deps beyond Druid) and unit-testable
 * standalone.
 */
@Component
public final class SqlValidator {

    private static final Logger log = LoggerFactory.getLogger(SqlValidator.class);

    /** Regex matching any FORBIDDEN keyword as a whole word (case-insensitive). */
    private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|MERGE|GRANT|REVOKE)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** Matches {@code --} line comments. */
    private static final Pattern LINE_COMMENT = Pattern.compile("--");

    /** Matches {@code /* ... *\/} block comments (DOTALL so it spans newlines). */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * Validate a candidate SQL string against the white-list rules.
     *
     * <p>Performs the regex pre-check first, then the Druid AST second pass.
     *
     * @param sql candidate SQL (may be null/empty)
     * @return validation result; on success {@code sanitizedSql} is the
     *         trimmed, single-spaced SQL; on failure {@code violations}
     *         lists human-readable reasons and {@code sanitizedSql} is empty
     */
    public ValidationResult validate(String sql) {
        List<String> violations = new ArrayList<>();

        if (sql == null || sql.isBlank()) {
            violations.add("SQL is empty");
            return new ValidationResult(false, violations, "");
        }

        String trimmed = sql.trim();

        // ─── Pass 1: Regex white-list (fast pre-check) ────────────────

        // Rule 1: must start with SELECT or WITH (case-insensitive).
        String upper = trimmed.toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            violations.add("SQL must start with SELECT or WITH");
        }

        // Rule 2: no FORBIDDEN keywords (word-boundary, case-insensitive).
        if (FORBIDDEN_KEYWORDS.matcher(trimmed).find()) {
            violations.add("SQL contains a forbidden DML/DDL keyword");
        }

        // Rule 3: no semicolons (prevents multi-statement injection).
        if (trimmed.indexOf(';') >= 0) {
            violations.add("SQL must not contain semicolons");
        }

        // Rule 4: no -- line comments.
        if (LINE_COMMENT.matcher(trimmed).find()) {
            violations.add("SQL must not contain line comments (--)");
        }

        // Rule 5: no /* */ block comments.
        if (BLOCK_COMMENT.matcher(trimmed).find()) {
            violations.add("SQL must not contain block comments (/* */)");
        }

        if (!violations.isEmpty()) {
            return new ValidationResult(false, violations, "");
        }

        // ─── Pass 2: Druid AST validation (D-190) ─────────────────────

        List<SQLStatement> astStatements = null;
        try {
            astStatements = SQLUtils.parseStatements(trimmed, DbType.trino);
        } catch (ParserException e) {
            violations.add("SQL failed AST parsing: " + e.getMessage());
            return new ValidationResult(false, violations, "");
        } catch (Exception e) {
            // Unexpected Druid error — fall back to regex-only validation.
            log.warn("[SqlValidator] Druid AST parsing error, falling back to regex-only: {}", e.getMessage());
            String sanitized = trimmed.replaceAll("\\s+", " ");
            return new ValidationResult(true, violations, sanitized);
        }

        // Check: exactly one statement.
        if (astStatements == null || astStatements.isEmpty()) {
            violations.add("SQL AST parsing produced no statements");
            return new ValidationResult(false, violations, "");
        }
        if (astStatements.size() > 1) {
            violations.add("SQL must be a single statement (got " + astStatements.size() + ")");
            return new ValidationResult(false, violations, "");
        }

        // Check: statement must be a SELECT.
        SQLStatement stmt = astStatements.get(0);
        if (!(stmt instanceof SQLSelectStatement)) {
            violations.add("SQL must be a SELECT statement (AST type: " + stmt.getClass().getSimpleName() + ")");
            return new ValidationResult(false, violations, "");
        }

        // Sanitize: collapse runs of whitespace into single spaces.
        String sanitized = trimmed.replaceAll("\\s+", " ");
        return new ValidationResult(true, violations, sanitized);
    }
}
