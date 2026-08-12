package com.ep.data.nlquery;

import java.util.List;

/**
 * Result of validating a candidate SQL string against the NL Query
 * white-list rules (ADR-D08, §12.4.3 security).
 *
 * <p>Implemented as a Java record per D-040 (GraalVM AOT compatible).
 *
 * @param valid         whether the SQL passed all white-list checks
 * @param violations    human-readable violation reasons (empty when valid)
 * @param sanitizedSql  trimmed, single-spaced SQL when valid; empty otherwise
 */
public record ValidationResult(
        boolean valid,
        List<String> violations,
        String sanitizedSql
) {
}
