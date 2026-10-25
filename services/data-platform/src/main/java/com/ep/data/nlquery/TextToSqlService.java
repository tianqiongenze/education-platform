package com.ep.data.nlquery;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Orchestrates the NL Query Text-to-SQL pipeline (ADR-D08, §12.4.1),
 * upgraded with Trino execution (step 5) and Iceberg audit (step 6) (D-190).
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Intent recognition: keyword-based classification into 统计 / 趋势 /
 *       对比 / 查询.</li>
 *   <li>Multi-turn follow-up: if {@link ConversationContext} can build a
 *       follow-up SQL from the session history, skip the LLM call.</li>
 *   <li>LLM generation: call {@link AiGatewayClient#chatCompletion} with a
 *       fixed system prompt instructing the model to emit a SELECT-only
 *       Trino SQL query.</li>
 *   <li>SQL validation: enforce the {@link SqlValidator} white-list + Druid
 *       AST second pass (D-190).</li>
 *   <li><b>Trino execution (D-190):</b> When the SQL is valid and the Trino
 *       executor is enabled (EP_DEV_ONLY=false), execute the query and
 *       capture the results. In dev/CI mode, this step is skipped.</li>
 *   <li><b>Iceberg audit (D-190):</b> Write an {@link NlQueryAuditRecord}
 *       via {@link IcebergAuditWriter} to {@code audit.nl_query_log} (or
 *       in-memory in dev/CI).</li>
 *   <li>Conversation recording: store the turn in {@link ConversationContext}.</li>
 * </ol>
 *
 * <p>Uses {@link ObjectProvider} for optional collaborators (TrinoExecutor,
 * IcebergAuditWriter, RateLimiter) so the service can be constructed even
 * when those beans aren't registered (dev/CI).
 *
 * <p>Thread-safety (D-039): no shared mutable state beyond the injected
 * collaborators.
 */
@Component
public class TextToSqlService {

    private static final Logger log = LoggerFactory.getLogger(TextToSqlService.class);

    private static final String SYSTEM_PROMPT =
            "You are a SQL generator. Generate only SELECT statements for Trino SQL. "
                    + "Based on the user question, generate a valid SELECT query.";

    /** Default max rows returned by Trino queries. */
    static final int DEFAULT_MAX_ROWS = 1000;

    private final AiGatewayClient aiGatewayClient;
    private final SqlValidator sqlValidator;
    private final ConversationContext conversationContext;
    private final ObjectProvider<TrinoExecutor> trinoExecutorProvider;
    private final ObjectProvider<IcebergAuditWriter> auditWriterProvider;
    private final ObjectProvider<RateLimiter> rateLimiterProvider;

    /** Dev/CI audit log (EP_DEV_ONLY guarded). Production uses IcebergAuditWriter. */
    private final java.util.concurrent.CopyOnWriteArrayList<String> legacyAuditLog =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Construct with explicit collaborators (constructor injection).
     *
     * @param aiGatewayClient      AI Gateway client
     * @param sqlValidator         SQL white-list + AST validator
     * @param conversationContext  multi-turn conversation context
     */
    @org.springframework.beans.factory.annotation.Autowired
    public TextToSqlService(AiGatewayClient aiGatewayClient,
                            SqlValidator sqlValidator,
                            ConversationContext conversationContext) {
        this(aiGatewayClient, sqlValidator, conversationContext, null, null, null);
    }

    /**
     * Construct with all D-190 collaborators.
     *
     * @param aiGatewayClient       AI Gateway client
     * @param sqlValidator          SQL white-list + AST validator
     * @param conversationContext   multi-turn conversation context
     * @param trinoExecutorProvider Trino executor (null for dev/CI)
     * @param auditWriterProvider   Iceberg audit writer (null for dev/CI)
     * @param rateLimiterProvider   Rate limiter (null for no rate limiting)
     */
    public TextToSqlService(AiGatewayClient aiGatewayClient,
                            SqlValidator sqlValidator,
                            ConversationContext conversationContext,
                            ObjectProvider<TrinoExecutor> trinoExecutorProvider,
                            ObjectProvider<IcebergAuditWriter> auditWriterProvider,
                            ObjectProvider<RateLimiter> rateLimiterProvider) {
        this.aiGatewayClient = aiGatewayClient;
        this.sqlValidator = sqlValidator;
        this.conversationContext = conversationContext;
        this.trinoExecutorProvider = trinoExecutorProvider;
        this.auditWriterProvider = auditWriterProvider;
        this.rateLimiterProvider = rateLimiterProvider;
    }

    /**
     * Run the NL Query Text-to-SQL pipeline for a single question.
     *
     * @param userId    user id (for audit logging and rate limiting)
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

        // Step 0: rate limiting (D-190).
        if (!checkRateLimit(userId)) {
            long latency = System.currentTimeMillis() - start;
            return new NlQueryResult(question, "查询", "", false,
                    List.of("Rate limit exceeded"), latency, sessionId);
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

        // Step 4: SQL validation (regex + Druid AST).
        ValidationResult validation = sqlValidator.validate(generatedSql);

        // Step 5: Trino execution (D-190).
        TrinoQueryResult queryResults = null;
        boolean executed = false;
        int rowCount = 0;
        if (validation.valid()) {
            queryResults = executeTrinoQuery(validation.sanitizedSql());
            if (queryResults != null && queryResults.success()) {
                executed = true;
                rowCount = queryResults.rowCount();
            }
        }

        long latency = System.currentTimeMillis() - start;

        // Step 6: audit logging (D-190).
        writeAuditRecord(userId, sessionId, question, intent, generatedSql,
                validation, executed, rowCount, latency);

        // Step 7: legacy audit log (EP_DEV_ONLY guarded — backward compat).
        legacyAuditLog(userId, question, intent, validation, latency, sessionId);

        // Step 8: record the turn in conversation context.
        if (sessionId != null && !sessionId.isBlank()) {
            String summary = validation.valid()
                    ? (executed ? "executed, " + rowCount + " rows" : "valid SQL generated")
                    : "invalid SQL: " + String.join("; ", validation.violations());
            ChatTurn turn = new ChatTurn(question, validation.sanitizedSql(), summary, LocalDateTime.now());
            conversationContext.addTurn(sessionId, turn);
        }

        return new NlQueryResult(
                question,
                intent,
                generatedSql,
                validation.valid(),
                validation.violations(),
                latency,
                sessionId,
                queryResults,
                executed,
                rowCount
        );
    }

    /**
     * Return a snapshot of the dev/CI legacy audit log (EP_DEV_ONLY guarded).
     *
     * @return snapshot of audit log entries
     */
    public List<String> getAuditLog() {
        return new ArrayList<>(legacyAuditLog);
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

    // ─── Internal helpers ─────────────────────────────────────────────

    private boolean checkRateLimit(String userId) {
        RateLimiter limiter = resolveRateLimiter();
        if (limiter == null) {
            return true; // no rate limiter → allow
        }
        return limiter.allowRequest(userId);
    }

    private TrinoQueryResult executeTrinoQuery(String sql) {
        TrinoExecutor executor = resolveTrinoExecutor();
        if (executor == null || !executor.isEnabled()) {
            return null;
        }
        return executor.execute(sql, DEFAULT_MAX_ROWS);
    }

    private void writeAuditRecord(String userId, String sessionId, String question,
                                  String intent, String generatedSql,
                                  ValidationResult validation, boolean executed,
                                  int rowCount, long latencyMs) {
        IcebergAuditWriter writer = resolveAuditWriter();
        if (writer == null) {
            return;
        }
        NlQueryAuditRecord record = new NlQueryAuditRecord(
                userId,
                sessionId,
                question,
                intent,
                generatedSql,
                validation.valid(),
                validation.violations().isEmpty() ? "" : String.join("; ", validation.violations()),
                executed,
                rowCount,
                latencyMs,
                LocalDateTime.now()
        );
        writer.write(record);
    }

    private TrinoExecutor resolveTrinoExecutor() {
        if (trinoExecutorProvider == null) {
            return null;
        }
        return trinoExecutorProvider.getIfAvailable();
    }

    private IcebergAuditWriter resolveAuditWriter() {
        if (auditWriterProvider == null) {
            return null;
        }
        return auditWriterProvider.getIfAvailable();
    }

    private RateLimiter resolveRateLimiter() {
        if (rateLimiterProvider == null) {
            return null;
        }
        return rateLimiterProvider.getIfAvailable();
    }

    private void legacyAuditLog(String userId, String question, String intent,
                                ValidationResult validation, long latency, String sessionId) {
        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);
        if (devGuard) {
            String entry = String.format(
                    "user=%s session=%s intent=%s valid=%s latency=%dms q=%s",
                    userId, sessionId, intent, validation.valid(), latency, truncate(question));
            legacyAuditLog.add(entry);
            log.info("[TextToSqlService] EP_DEV_ONLY active — audit logged: {}", entry);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 100 ? s : s.substring(0, 100) + "...";
    }
}
