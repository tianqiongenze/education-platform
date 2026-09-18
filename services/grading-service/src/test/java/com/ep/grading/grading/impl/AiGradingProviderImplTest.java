package com.ep.grading.grading.impl;

import com.ep.grading.api.grading.AiGradingRequest;
import com.ep.grading.api.grading.AiGradingResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-091 unit tests for {@link AiGradingProviderImpl}.
 *
 * <p>Coverage strategy: the provider exposes two {@code protected} hooks —
 * {@link AiGradingProviderImpl#isDevOnly()} (EP_DEV_ONLY gate) and
 * {@link AiGradingProviderImpl#callGateway(String, String)} (the HTTP hop) —
 * so tests subclass and override them with canned values. No junit-pioneer,
 * no MockWebServer, no Spring context, no real network.
 *
 * <p>This also lets us assert the deterministic stub formula directly and
 * exercise both the JSON-content and free-text-content parse branches.
 */
class AiGradingProviderImplTest {

    private static final String LONG_ANSWER =
            "Photosynthesis converts light energy into chemical energy stored in glucose, "
                    + "using carbon dioxide and water while releasing oxygen as a by-product.";

    private AiGradingRequest req(String answer) {
        return new AiGradingRequest(1L, "Explain photosynthesis", answer, 100, "rubric-7", "t-1");
    }

    /** Inject the gateway URL into the @Value field without standing up Spring. */
    private static void setGatewayUrl(AiGradingProviderImpl impl, String url) {
        try {
            Field f = AiGradingProviderImpl.class.getDeclaredField("gatewayUrl");
            f.setAccessible(true);
            f.set(impl, url);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A provider locked into dev-stub mode (no HTTP). */
    private static AiGradingProviderImpl devModeProvider() {
        AiGradingProviderImpl impl = new AiGradingProviderImpl() {
            @Override
            protected boolean isDevOnly() {
                return true;
            }
        };
        setGatewayUrl(impl, "http://localhost:8000");
        return impl;
    }

    /** A provider whose HTTP hop returns a canned content string. */
    private static AiGradingProviderImpl providerReturning(String content) {
        AiGradingProviderImpl impl = new AiGradingProviderImpl() {
            @Override
            protected boolean isDevOnly() {
                return false;
            }

            @Override
            protected String callGateway(String systemPrompt, String userMessage) {
                return content;
            }
        };
        setGatewayUrl(impl, "http://localhost:8000");
        return impl;
    }

    /** A provider whose HTTP hop throws, simulating a transport/HTTP failure. */
    private static AiGradingProviderImpl providerThrowing(RuntimeException ex) {
        AiGradingProviderImpl impl = new AiGradingProviderImpl() {
            @Override
            protected boolean isDevOnly() {
                return false;
            }

            @Override
            protected String callGateway(String systemPrompt, String userMessage) {
                throw ex;
            }
        };
        setGatewayUrl(impl, "http://localhost:8000");
        return impl;
    }

    // ---- EP_DEV_ONLY stub path ------------------------------------------------

    @Test
    void test_dev_mode_returns_stub_grade() {
        AiGradingProviderImpl impl = devModeProvider();

        AiGradingResult result = impl.grade(req("short answer"));

        assertNotNull(result);
        assertTrue(result.score() >= 0 && result.score() <= 100,
                "stub score must be within rubric ceiling");
        assertNotNull(result.feedback());
        assertTrue(result.feedback().startsWith("Dev mode: AI grading stub."),
                "stub feedback must be the dev-mode marker, got: " + result.feedback());
        assertNotNull(result.gradedAt());
        assertEquals(1.0, result.confidence());
    }

    @Test
    void test_dev_mode_long_answer_higher_score() {
        AiGradingProviderImpl impl = devModeProvider();

        AiGradingResult result = impl.grade(req(LONG_ANSWER));

        // Long answer (len>20) → raw=len*2 must push score strictly above the 40 floor.
        assertTrue(result.score() > 40,
                "long answer should score higher than the floor, got " + result.score());
        assertTrue(result.score() <= 80, "stub score is capped at 80");
    }

    @Test
    void test_dev_mode_short_answer_lower_score() {
        AiGradingProviderImpl impl = devModeProvider();

        AiGradingResult result = impl.grade(req("hi")); // len=2, raw=4 → clamped to floor 40

        assertEquals(40.0, result.score(),
                "very short answer should collapse to the 40 floor");
    }

    @Test
    void test_dev_mode_stub_respects_lower_ceiling() {
        // When maxScore < 80, the stub upper bound collapses to the ceiling.
        AiGradingProviderImpl impl = devModeProvider();
        AiGradingRequest smallCeiling = new AiGradingRequest(
                2L, "q", LONG_ANSWER, 30, "r", "t-1");

        AiGradingResult result = impl.grade(smallCeiling);

        assertTrue(result.score() <= 30,
                "stub score must respect a sub-80 rubric ceiling, got " + result.score());
    }

    @Test
    void test_is_dev_only_predicate_interpretation() {
        // Mirrors DevGuardConfig: unset/blank/"false"/"0" → inactive; anything else → active.
        assertFalse(AiGradingProviderImpl.isDevOnlyActive(null));
        assertFalse(AiGradingProviderImpl.isDevOnlyActive(""));
        assertFalse(AiGradingProviderImpl.isDevOnlyActive("   "));
        assertFalse(AiGradingProviderImpl.isDevOnlyActive("false"));
        assertFalse(AiGradingProviderImpl.isDevOnlyActive("FALSE"));
        assertFalse(AiGradingProviderImpl.isDevOnlyActive("0"));
        assertTrue(AiGradingProviderImpl.isDevOnlyActive("true"));
        assertTrue(AiGradingProviderImpl.isDevOnlyActive("1"));
        assertTrue(AiGradingProviderImpl.isDevOnlyActive("yes"));
        assertTrue(AiGradingProviderImpl.isDevOnlyActive("anything-nonblank"));
    }

    // ---- validation / null handling ------------------------------------------

    @Test
    void test_null_request_throws() {
        AiGradingProviderImpl impl = devModeProvider();
        assertThrows(NullPointerException.class, () -> impl.grade(null));
    }

    @Test
    void test_null_answer_returns_zero_without_ai_call() {
        AiGradingProviderImpl impl = devModeProvider();
        AiGradingRequest nullAnswer = new AiGradingRequest(3L, "q", null, 100, "r", "t-1");

        AiGradingResult result = impl.grade(nullAnswer);

        assertEquals(0.0, result.score());
        assertEquals("No answer submitted", result.feedback());
    }

    @Test
    void test_empty_answer_returns_zero() {
        AiGradingProviderImpl impl = devModeProvider();

        AiGradingResult result = impl.grade(req("   "));

        assertEquals(0.0, result.score());
        assertEquals("No answer submitted", result.feedback());
    }

    // ---- live HTTP path (callGateway overridden) ------------------------------

    @Test
    void test_valid_response_parsed() {
        // LLM returns a clean JSON object in content.
        AiGradingProviderImpl impl = providerReturning(
                "{\"score\": 78, \"feedback\": \"Good analysis of photosynthesis.\"}");

        AiGradingResult result = impl.grade(req(LONG_ANSWER));

        assertEquals(78.0, result.score());
        assertEquals("Good analysis of photosynthesis.", result.feedback());
        assertNotNull(result.gradedAt());
    }

    @Test
    void test_non_json_response_extracts_score() {
        // LLM ignores the JSON instruction and returns prose with a number.
        AiGradingProviderImpl impl = providerReturning("Score: 75. Good work on the light reactions.");

        AiGradingResult result = impl.grade(req(LONG_ANSWER));

        assertEquals(75.0, result.score());
        assertEquals("Score: 75. Good work on the light reactions.", result.feedback());
    }

    @Test
    void test_response_with_markdown_fence_json() {
        // Common LLM habit: wrap JSON in ```json fences.
        AiGradingProviderImpl impl = providerReturning(
                "Here is my grade:\n```json\n{\"score\": 90, \"feedback\": \"Excellent.\"}\n```\nThanks.");

        AiGradingResult result = impl.grade(req(LONG_ANSWER));

        assertEquals(90.0, result.score());
        assertEquals("Excellent.", result.feedback());
    }

    @Test
    void test_http_error_throws_runtime() {
        AiGradingProviderImpl impl = providerThrowing(
                new RuntimeException("AI gateway returned HTTP 503: upstream timeout"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> impl.grade(req(LONG_ANSWER)));
        assertTrue(ex.getMessage().contains("503"),
                "HTTP failure must surface to the caller for fallback handling");
    }

    @Test
    void test_clamps_score_above_ceiling() {
        // LLM over-reports (score 120 vs ceiling 100) — provider must clamp.
        AiGradingProviderImpl impl = providerReturning("{\"score\": 120, \"feedback\": \"Over.\"}");
        AiGradingRequest r = new AiGradingRequest(4L, "q", LONG_ANSWER, 100, "r", "t-1");

        AiGradingResult result = impl.grade(r);

        assertEquals(100.0, result.score(), "score above ceiling must be clamped to maxScore");
    }

    @Test
    void test_missing_score_field_scores_zero() {
        AiGradingProviderImpl impl = providerReturning("{\"feedback\": \"No number given.\"}");

        AiGradingResult result = impl.grade(req(LONG_ANSWER));

        assertEquals(0.0, result.score(), "missing score must not over-grade");
        assertEquals("No number given.", result.feedback());
    }

    @Test
    void test_empty_content_returns_neutral_result() {
        AiGradingProviderImpl impl = providerReturning("");

        AiGradingResult result = impl.grade(req(LONG_ANSWER));

        assertEquals(0.0, result.score());
        assertEquals("AI returned empty feedback", result.feedback());
    }

    @Test
    void test_text_with_no_valid_number_scores_zero() {
        // Prose with no 0-100 number anywhere → 0 (no lucky hit on a stray year).
        AiGradingProviderImpl impl = providerReturning("The student answered reasonably well.");

        AiGradingResult result = impl.grade(req(LONG_ANSWER));

        assertEquals(0.0, result.score());
    }

    @Test
    void test_text_with_out_of_range_number_ignored() {
        // A number > 100 must be skipped, not used as the score.
        AiGradingProviderImpl impl = providerReturning(
                "Year 2024 essay. The student scored 82 overall.");

        AiGradingResult result = impl.grade(req(LONG_ANSWER));

        assertEquals(82.0, result.score(),
                "out-of-range numbers (>100) must be skipped in favor of the first valid 0-100 score");
    }

    @Test
    void test_build_user_message_contains_rubric_and_answer() {
        AiGradingRequest r = new AiGradingRequest(9L, "Explain X", "my essay", 80, "rubric-42", "t-9");
        String msg = AiGradingProviderImpl.buildUserMessage(r, 80);

        assertTrue(msg.contains("Rubric: rubric-42"));
        assertTrue(msg.contains("Max Score: 80"));
        assertTrue(msg.contains("Student Answer: my essay"));
        assertTrue(msg.contains("Question: Explain X"));
    }
}
