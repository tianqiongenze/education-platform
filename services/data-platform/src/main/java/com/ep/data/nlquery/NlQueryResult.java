package com.ep.data.nlquery;

import java.util.List;

/**
 * Result of a single NL Query Text-to-SQL invocation (ADR-D08, §12.4.1),
 * upgraded with Trino execution results (D-190).
 *
 * <p>Carries the original question, the classified intent, the generated SQL,
 * the validation outcome, end-to-end latency, the session id used for
 * multi-turn context, and optionally the Trino query results when the SQL
 * was executed.
 *
 * <p>Implemented as a Java record per D-040 (GraalVM AOT compatible).
 *
 * @param question      the original natural-language question
 * @param intent        classified intent (查询/统计/趋势/对比)
 * @param generatedSql  raw SQL returned by the LLM (or template SQL in dev/CI)
 * @param isValid       whether the generated SQL passed white-list validation
 * @param violations    validation violation reasons (empty when valid)
 * @param latencyMs     end-to-end pipeline latency in milliseconds
 * @param sessionId     conversation session id (may be null/blank)
 * @param queryResults  Trino query results (null when not executed or in dev mode)
 * @param executed      whether the SQL was executed against Trino
 * @param rowCount      number of rows returned (0 if not executed)
 */
public record NlQueryResult(
        String question,
        String intent,
        String generatedSql,
        boolean isValid,
        List<String> violations,
        long latencyMs,
        String sessionId,
        TrinoQueryResult queryResults,
        boolean executed,
        int rowCount
) {

    /**
     * Backward-compatible constructor for callers that don't need Trino
     * results (e.g. dev/CI mode or pre-D-190 tests).
     */
    public NlQueryResult(String question, String intent, String generatedSql,
                         boolean isValid, List<String> violations,
                         long latencyMs, String sessionId) {
        this(question, intent, generatedSql, isValid, violations, latencyMs, sessionId,
                null, false, 0);
    }
}
