package com.ep.data.nlquery;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Orchestrates the NL Query Text-to-SQL pipeline (ADR-D08, §12.4.1).
 *
 * <p>Pipeline (simplified — no Trino execution in the MVP):
 * <ol>
 *   <li>Intent recognition: keyword-based classification into 统计 / 趋势 /
 *       对比 / 查询.</li>
 *   <li>Multi-turn follow-up: if {@link ConversationContext} can build a
 *       follow-up SQL from the session history, skip the LLM call.</li>
 *   <li>LLM generation: call {@link AiGatewayClient#chatCompletion} with a
 *       fixed system prompt instructing the model to emit a SELECT-only
 *       Trino SQL query.</li>
 *   <li>SQL validation: enforce the {@link SqlValidator} white-list.</li>
 *   <li>Conversation recording: store the turn in {@link ConversationContext}.</li>
 *   <li>Audit logging: under the EP_DEV_ONLY guard, log to an in-memory list;
 *       production would write to {@code audit.nl_query_log}.</li>
 * </ol>
 *
 * <p>Thread-safety (D-039): the audit log is a {@link java.util.concurrent.CopyOnWriteArrayList} —
 * no synchronized blocks.
 */
@Component
public class TextToSqlService {

    private static final Logger log = LoggerFactory.getLogger(TextToSqlService.class);

    private static final String SYSTEM_PROMPT =
            "You are a SQL generator. Generate only SELECT statements for Trino SQL. "
                    + "Based on the user question, generate a valid SELECT query.";

    private final AiGatewayClient aiGatewayClient;
    private final SqlValidator sqlValidator;
    private final ConversationContext conversationContext;

    /** Dev/CI audit log (EP_DEV_ONLY guarded). Production writes to audit.nl_query_log. */
    private final java.util.concurrent.CopyOnWriteArrayList<String> auditLog =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Construct with explicit collaborators (constructor injection).
     *
     * @param aiGatewayClient     SP-04 AI Gateway client
     * @param sqlValidator        SQL white-list validator
     * @param conversationContext multi-turn conversation context
     */
    public TextToSqlService(AiGatewayClient aiGatewayClient,
                            SqlValidator sqlValidator,
                            ConversationContext conversationContext) {
        this.aiGatewayClient = aiGatewayClient;
        this.sqlValidator = sqlValidator;
        this.conversationContext = conversationContext;
    }

    /**
     * Run the NL Query Text-to-SQL pipeline for a single question.
     *
     * @param userId    user id (for audit logging)
     * @param question  natural-language question
     * @param sessionId conversation session id (may be null/blank for one-shot)
     * @return the query result with timing; never null
     */
    public NlQueryResult query(String userId, String question, String sessionId) {
        long start = System.currentTimeMillis();

        if (question == null || question.isBlank()) {
            long latency = System.currentTimeMillis() - start;
            return new NlQueryResult(question, "查询", "", false,
                    List.of("Question is empty"), latency, sessionId);
        }

        // Step 1: intent recognition (keyword classification).
        String intent = classifyIntent(question);

        // Step 2: try follow-up from conversation context.
        String generatedSql = null;
        if (sessionId != null && !sessionId.isBlank()) {
            generatedSql = conversationContext.buildFollowupSql(sessionId, question);
        }

        // Step 3: LLM generation (only if no follow-up).
        if (generatedSql == null) {
            generatedSql = aiGatewayClient.chatCompletion(SYSTEM_PROMPT, question);
        }
        if (generatedSql == null) {
            generatedSql = "";
        }

        // Step 5: SQL validation.
        ValidationResult validation = sqlValidator.validate(generatedSql);

        // Step 6: record the turn in conversation context.
        if (sessionId != null && !sessionId.isBlank()) {
            String summary = validation.valid()
                    ? "valid SQL generated"
                    : "invalid SQL: " + String.join("; ", validation.violations());
            ChatTurn turn = new ChatTurn(question, validation.sanitizedSql(), summary, LocalDateTime.now());
            conversationContext.addTurn(sessionId, turn);
        }

        long latency = System.currentTimeMillis() - start;

        // Step 7: audit log (EP_DEV_ONLY guarded).
        auditLog(userId, question, intent, validation, latency, sessionId);

        return new NlQueryResult(
                question,
                intent,
                generatedSql,
                validation.valid(),
                validation.violations(),
                latency,
                sessionId
        );
    }

    /**
     * Return a snapshot of the dev/CI audit log (EP_DEV_ONLY guarded).
     *
     * <p>In production this would query the {@code audit.nl_query_log} table;
     * under the EP_DEV_ONLY guard it returns the in-memory log.
     *
     * @return snapshot of audit log entries
     */
    public List<String> getAuditLog() {
        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);
        if (devGuard) {
            return new ArrayList<>(auditLog);
        }
        // Production: would query audit.nl_query_log (not implemented in MVP).
        log.info("[TextToSqlService] EP_DEV_ONLY inactive — production audit log read not implemented in MVP");
        return new ArrayList<>();
    }

    /**
     * Classify the user intent from keywords (§12.4.1).
     *
     * @param question natural-language question
     * @return one of 统计 / 趋势 / 对比 / 查询
     */
    static String classifyIntent(String question) {
        if (question == null || question.isBlank()) {
            return "查询";
        }
        String lower = question.toLowerCase();
        if (lower.contains("count") || question.contains("多少") || question.contains("数量")) {
            return "统计";
        }
        if (lower.contains("trend") || question.contains("趋势") || question.contains("变化")) {
            return "趋势";
        }
        if (lower.contains("compare") || question.contains("对比") || question.contains("比较")) {
            return "对比";
        }
        return "查询";
    }

    private void auditLog(String userId, String question, String intent,
                          ValidationResult validation, long latency, String sessionId) {
        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);
        if (devGuard) {
            String entry = String.format(
                    "user=%s session=%s intent=%s valid=%s latency=%dms q=%s",
                    userId, sessionId, intent, validation.valid(), latency, truncate(question));
            auditLog.add(entry);
            log.info("[TextToSqlService] EP_DEV_ONLY active — audit logged: {}", entry);
        } else {
            // Production: would write to audit.nl_query_log (not implemented in MVP).
            log.info("[TextToSqlService] EP_DEV_ONLY inactive — production audit write not implemented in MVP");
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 100 ? s : s.substring(0, 100) + "...";
    }
}
