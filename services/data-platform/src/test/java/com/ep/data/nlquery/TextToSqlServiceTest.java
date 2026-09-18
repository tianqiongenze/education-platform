package com.ep.data.nlquery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link TextToSqlService}.
 *
 * <p>No Spring context. The service is wired with plain-POJO collaborators
 * ({@link AiGatewayClient}, {@link SqlValidator}, {@link ConversationContext})
 * instantiated with {@code new}.
 *
 * <p>Because {@code EP_DEV_ONLY} is typically unset in CI, the
 * {@link AiGatewayClient} dev guard is active and returns template SQL — so
 * the tests verify the dev/CI path end-to-end without a running AI gateway.
 */
class TextToSqlServiceTest {

    private AiGatewayClient aiGatewayClient;
    private SqlValidator sqlValidator;
    private ConversationContext conversationContext;
    private TextToSqlService service;

    @BeforeEach
    void setUp() {
        aiGatewayClient = new AiGatewayClient();
        sqlValidator = new SqlValidator();
        conversationContext = new ConversationContext();
        service = new TextToSqlService(aiGatewayClient, sqlValidator, conversationContext);
    }

    @Test
    void test_dev_mode_returns_template_sql() {
        // EP_DEV_ONLY unset in CI → devGuard=true → template SQL from AiGatewayClient.
        NlQueryResult result = service.query("user1", "list all users", "sess-1");
        assertNotNull(result, "result should not be null");
        assertFalse(result.generatedSql().isBlank(),
                "dev mode should produce non-blank template SQL, got: " + result.generatedSql());
        assertTrue(result.generatedSql().startsWith("SELECT"),
                "template SQL should start with SELECT, got: " + result.generatedSql());
    }

    @Test
    void test_intent_classification_query() {
        NlQueryResult result = service.query("user1", "列出所有用户", null);
        assertEquals("查询", result.intent(),
                "列出所有用户 should classify as 查询, got: " + result.intent());
    }

    @Test
    void test_intent_classification_stat() {
        NlQueryResult result = service.query("user1", "有多少用户", null);
        assertEquals("统计", result.intent(),
                "有多少用户 should classify as 统计, got: " + result.intent());
    }

    @Test
    void test_intent_classification_trend() {
        NlQueryResult result = service.query("user1", "用户增长趋势", null);
        assertEquals("趋势", result.intent(),
                "用户增长趋势 should classify as 趋势, got: " + result.intent());
    }

    @Test
    void test_intent_classification_compare() {
        NlQueryResult result = service.query("user1", "对比上月数据", null);
        assertEquals("对比", result.intent(),
                "对比上月数据 should classify as 对比, got: " + result.intent());
    }

    @Test
    void test_intent_classification_english_count() {
        NlQueryResult result = service.query("user1", "count users", null);
        assertEquals("统计", result.intent(),
                "count users should classify as 统计, got: " + result.intent());
    }

    @Test
    void test_sql_validation_failure_handled() {
        // Inject a client that returns invalid SQL (DELETE statement) to exercise
        // the validator's failure path. The validator should reject it.
        AiGatewayClient badClient = new AiGatewayClient() {
            @Override
            public String chatCompletion(String systemPrompt, String userMessage) {
                return "DELETE FROM users WHERE 1=1";
            }
        };
        TextToSqlService svc = new TextToSqlService(badClient, sqlValidator, conversationContext);
        NlQueryResult result = svc.query("user1", "delete everything", "sess-bad");
        assertFalse(result.isValid(),
                "DELETE SQL should be invalid, got isValid=" + result.isValid());
        assertFalse(result.violations().isEmpty(),
                "violations should not be empty for DELETE, got: " + result.violations());
    }

    @Test
    void test_audit_log_recorded() {
        service.query("user1", "list all users", "sess-audit");
        var log = service.getAuditLog();
        assertFalse(log.isEmpty(),
                "audit log should have at least one entry after a query, got size=" + log.size());
        String entry = log.get(log.size() - 1);
        assertTrue(entry.contains("user=user1"),
                "audit entry should contain user=user1, got: " + entry);
        assertTrue(entry.contains("session=sess-audit"),
                "audit entry should contain session=sess-audit, got: " + entry);
    }

    @Test
    void test_followup_uses_context() {
        // First query seeds the session with template SQL.
        NlQueryResult first = service.query("user1", "list all users", "sess-followup");
        assertTrue(first.isValid(), "first query SQL should be valid, got: " + first.violations());
        assertEquals("SELECT * FROM default_table LIMIT 100", first.generatedSql(),
                "first query should produce list template, got: " + first.generatedSql());

        // Second query with "where" keyword → follow-up should append AND 1=1
        // and skip the LLM call.
        NlQueryResult second = service.query("user1", "where active = true", "sess-followup");
        assertTrue(second.generatedSql().contains("AND 1=1"),
                "followup should append AND 1=1 to last SQL, got: " + second.generatedSql());
    }

    @Test
    void test_empty_question_handled() {
        NlQueryResult result = service.query("user1", "", "sess-empty");
        assertFalse(result.isValid(), "empty question should yield invalid result");
        assertFalse(result.violations().isEmpty(),
                "empty question should have violations, got: " + result.violations());
    }

    @Test
    void test_null_question_handled() {
        NlQueryResult result = service.query("user1", null, "sess-null");
        assertFalse(result.isValid(), "null question should yield invalid result");
    }

    @Test
    void test_result_contains_session_id() {
        NlQueryResult result = service.query("user1", "list users", "sess-id-123");
        assertEquals("sess-id-123", result.sessionId(),
                "result sessionId should match input, got: " + result.sessionId());
    }

    @Test
    void test_latency_recorded() {
        NlQueryResult result = service.query("user1", "list users", "sess-latency");
        assertTrue(result.latencyMs() >= 0,
                "latencyMs should be non-negative, got: " + result.latencyMs());
    }
}
