package com.ep.data.nlquery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link AiGatewayClient}.
 *
 * <p>These tests verify the dev/CI behavior: because {@code EP_DEV_ONLY} is
 * typically unset in CI, the {@link AiGatewayClient} dev guard is active and
 * no real HTTP call is made — the client returns template SQL derived from
 * keywords in the user question.
 *
 * <p>No Spring context, no running SP-04 gateway required.
 */
class AiGatewayClientTest {

    private final AiGatewayClient client = new AiGatewayClient();

    @Test
    void test_dev_mode_skips_http() {
        // EP_DEV_ONLY is unset in CI → devGuard=true → no HTTP call.
        String result = client.chatCompletion("system prompt", "show me all users");
        assertNotNull(result);
        assertFalse(result.isBlank(), "dev mode should return non-blank template SQL, got: " + result);
        assertTrue(result.startsWith("SELECT"),
                "dev mode template SQL should start with SELECT, got: " + result);
    }

    @Test
    void test_dev_mode_count_keyword() {
        String result = client.chatCompletion("system", "count users");
        assertEquals("SELECT COUNT(*) FROM default_table", result,
                "count keyword should yield COUNT(*) template, got: " + result);
    }

    @Test
    void test_dev_mode_list_keyword() {
        String result = client.chatCompletion("system", "list all users");
        assertEquals("SELECT * FROM default_table LIMIT 100", result,
                "list keyword should yield LIMIT 100 template, got: " + result);
    }

    @Test
    void test_dev_mode_chinese_duoshao_keyword() {
        String result = client.chatCompletion("system", "有多少用户");
        assertEquals("SELECT COUNT(*) FROM default_table", result,
                "多少 keyword should yield COUNT(*) template, got: " + result);
    }

    @Test
    void test_dev_mode_chinese_liechu_keyword() {
        String result = client.chatCompletion("system", "列出所有用户");
        assertEquals("SELECT * FROM default_table LIMIT 100", result,
                "列出 keyword should yield LIMIT 100 template, got: " + result);
    }

    @Test
    void test_dev_mode_default_template() {
        String result = client.chatCompletion("system", "show me something");
        assertEquals("SELECT * FROM default_table LIMIT 10", result,
                "no keyword should yield LIMIT 10 template, got: " + result);
    }

    @Test
    void test_null_inputs_return_empty() {
        String r1 = client.chatCompletion(null, "question");
        assertEquals("", r1, "null systemPrompt should return empty string, got: " + r1);
        String r2 = client.chatCompletion("system", null);
        assertEquals("", r2, "null userMessage should return empty string, got: " + r2);
    }

    @Test
    void test_config_from_env_defaults() {
        NlQueryConfig config = NlQueryConfig.fromEnv();
        assertEquals("http://localhost:8000", config.gatewayUrl(),
                "default gatewayUrl should be http://localhost:8000, got: " + config.gatewayUrl());
        assertEquals("gpt-4o", config.model(),
                "default model should be gpt-4o, got: " + config.model());
        assertEquals(0.0, config.temperature(),
                "default temperature should be 0.0, got: " + config.temperature());
        assertEquals(10, config.timeoutSec(),
                "default timeoutSec should be 10, got: " + config.timeoutSec());
        assertEquals(4096, config.maxTokens(),
                "default maxTokens should be 4096, got: " + config.maxTokens());
    }

    @Test
    void test_config_gateway_url_has_no_path() {
        NlQueryConfig config = NlQueryConfig.fromEnv();
        assertFalse(config.gatewayUrl().contains("/v1/chat/completions"),
                "gatewayUrl should NOT include the chat-completions path, got: " + config.gatewayUrl());
    }
}
