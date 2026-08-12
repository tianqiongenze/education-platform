package com.ep.data.nlquery;

import java.time.LocalDateTime;

/**
 * A single turn in a multi-turn NL Query conversation (ADR-D08, §12.4.2).
 *
 * <p>Records the question, the generated SQL, a short summary of the query
 * result, and the timestamp at which the turn occurred. Conversation history
 * is held in-memory by {@link ConversationContext} (dev/CI fallback;
 * production would persist to {@code audit.nl_query_log}).
 *
 * <p>Implemented as a Java record per D-040 (GraalVM AOT compatible).
 *
 * @param question       the original natural-language question
 * @param sql            the generated (and validated) SQL
 * @param resultSummary  short human-readable summary of the query result
 * @param timestamp      when the turn occurred
 */
public record ChatTurn(
        String question,
        String sql,
        String resultSummary,
        LocalDateTime timestamp
) {
}
