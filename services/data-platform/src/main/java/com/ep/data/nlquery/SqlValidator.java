package com.ep.data.nlquery;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Pure Java SQL white-list validator for the NL Query Text-to-SQL module
 * (ADR-D08, §12.4.3 security).
 *
 * <p>This class enforces a strict white-list of permitted SQL constructs so
 * that LLM-generated SQL cannot perform destructive operations or inject
 * additional statements. The rules (§12.4.3) are:
 * <ol>
 *   <li>Must start with {@code SELECT} or {@code WITH} (case-insensitive,
 *       after trimming leading whitespace).</li>
 *   <li>FORBIDDEN keywords (regex, case-insensitive, word-boundary):
 *       {@code INSERT}, {@code UPDATE}, {@code DELETE}, {@code DROP},
 *       {@code CREATE}, {@code ALTER}, {@code TRUNCATE}, {@code MERGE},
 *       {@code GRANT}, {@code REVOKE}.</li>
 *   <li>No semicolons — prevents multi-statement injection.</li>
 *   <li>No {@code --} line comments.</li>
 *   <li>No {@code /* ... *\/} block comments.</li>
 * </ol>
 *
 * <p>This class is pure (no Spring/IO deps) and unit-testable standalone —
 * same pattern as {@code PSICalculator}.
 */
@Component
public final class SqlValidator {

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

        // Sanitize: collapse runs of whitespace into single spaces.
        String sanitized = trimmed.replaceAll("\\s+", " ");
        return new ValidationResult(true, violations, sanitized);
    }
}
