package com.ep.grading.grading.impl;

import com.ep.grading.api.grading.AiGradingRequest;
import com.ep.grading.api.grading.AiGradingResult;
import com.ep.grading.api.grading.AiGradingRpcService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java Dubbo provider shim for {@link AiGradingRpcService} (D-091).
 *
 * <p>SP-04 is a Python/Litestar service that cannot serve a Java Dubbo Triple
 * contract. This provider bridges the gap: it receives the Dubbo call from
 * sp05's {@code GradingRpcServiceImpl#gradeSubjective}, then calls SP-04's
 * HTTP {@code POST /v1/chat/completions} endpoint with a grading-specific
 * system prompt, parses the {@code choices[0].message.content} payload, and
 * returns an {@link AiGradingResult}.
 *
 * <h3>EP_DEV_ONLY guard</h3>
 * When the {@code EP_DEV_ONLY} env var is active (unset/blank treated as
 * <em>inactive</em>; {@code "true"}/{@code "1"}/non-blank treated as active —
 * mirroring {@code DevGuardConfig}), this provider skips the HTTP call and
 * returns a deterministic stub grade based on answer length (short → lower
 * score, substantial → higher score). This lets dev/CI run without SP-04.
 *
 * <h3>Iron-law compliance</h3>
 * <ul>
 *   <li>Iron Law 6: {@code @DubboService} on Dubbo 3.3.6, registered to Nacos.</li>
 *   <li>Iron Law 7: stateless, {@code final} fields, no reflection; safe under
 *       Java 21 virtual threads and GraalVM AOT.</li>
 *   <li>D-039: no {@code synchronized} blocks.</li>
 *   <li>D-040: GraalVM AOT compatible — JDK {@link HttpClient} is AOT-safe,
 *       Jackson is registered for build-time init via the native-maven-plugin.</li>
 * </ul>
 *
 * <p>Failure semantics: on HTTP/parse failure this provider throws a
 * {@link RuntimeException} so the sp05 consumer catches it and applies the
 * configured fallback ({@code pending}/{@code fail}).
 */
@DubboService
public class AiGradingProviderImpl implements AiGradingRpcService {

    private static final Logger log = LoggerFactory.getLogger(AiGradingProviderImpl.class);

    /**
     * System prompt instructing the LLM to grade against the rubric and return
     * a JSON object with {@code score} and {@code feedback}.
     */
    static final String SYSTEM_PROMPT =
            "You are an AI grading assistant. Grade the student's subjective answer against the rubric. "
                    + "Return a JSON object with 'score' (0-100) and 'feedback' (text).";

    /**
     * Regex extracting the first integer 0-100 out of a free-text response, so
     * a non-JSON LLM answer still yields a usable score. Bound to 0-100 so a
     * stray 200/1000 does not inflate the rubric ceiling.
     */
    private static final Pattern SCORE_PATTERN = Pattern.compile("\\b(\\d{1,3})(?:\\.\\d+)?\\b");

    /**
     * Shared, thread-safe ObjectMapper. Jackson's mapper is documented
     * thread-safe after configuration; one instance per JVM is fine.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${sp04.gateway-url:http://localhost:8000}")
    private String gatewayUrl;

    /**
     * Eager single-client. {@link HttpClient} is thread-safe, virtual-thread
     * friendly, and cheap to hold; building it up-front avoids any lazy-init
     * synchronization (D-039: no synchronized blocks) and keeps the type a
     * simple immutable POJO for GraalVM AOT (D-040).
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public AiGradingResult grade(AiGradingRequest request) {
        Objects.requireNonNull(request, "AiGradingRequest must not be null");

        String answer = request.studentAnswer();
        Integer maxScore = request.maxScore();
        int ceiling = maxScore == null ? 100 : maxScore;

        if (answer == null || answer.isBlank()) {
            // Never burn an AI call on an empty submission.
            return new AiGradingResult(0.0, "No answer submitted", 0.0, nowUtc());
        }

        if (isDevOnly()) {
            return stubGrade(answer, ceiling);
        }

        String userMessage = buildUserMessage(request, ceiling);
        String content;
        try {
            content = callGateway(SYSTEM_PROMPT, userMessage);
        } catch (RuntimeException e) {
            // Re-throw so GradingRpcServiceImpl#gradeSubjective applies the fallback.
            log.warn("SP-04 gateway call failed for submission {}: {}",
                    request.submissionId(), e.getMessage());
            throw e;
        }
        return parseContent(content, ceiling);
    }

    /**
     * Build the OpenAI-style user message carrying the rubric, max score, and
     * student answer. Exposed package-private so tests can verify message
     * shape without standing up an HTTP mock.
     */
    static String buildUserMessage(AiGradingRequest request, int ceiling) {
        return "Rubric: " + nullToEmpty(request.rubric())
                + "\nMax Score: " + ceiling
                + "\nQuestion: " + nullToEmpty(request.questionContent())
                + "\nStudent Answer: " + nullToEmpty(request.studentAnswer());
    }

    /**
     * Call SP-04's {@code /v1/chat/completions} endpoint and return the
     * assistant {@code content} string.
     *
     * <p>Protected so unit tests can override it with a stub that returns a
     * canned response (or throws) without standing up a real HTTP server.
     * This keeps the test surface pure-JUnit/Mockito with zero new deps.
     *
     * @throws RuntimeException on any transport/HTTP failure, so the caller can
     *                          apply its configured fallback strategy.
     */
    protected String callGateway(String systemPrompt, String userMessage) {
        String url = resolveEndpoint();
        String body = buildRequestBody(systemPrompt, userMessage);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("SP-04 gateway call failed: " + e.getMessage(), e);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new RuntimeException(
                    "SP-04 gateway returned HTTP " + status + ": " + safeBodyPreview(response.body()));
        }
        return extractContent(response.body());
    }

    /**
     * Extract {@code choices[0].message.content} from the OpenAI-style
     * response body. Package-private so tests can drive it directly.
     */
    String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new RuntimeException("SP-04 response missing choices[]");
            }
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new RuntimeException("SP-04 response missing choices[0].message.content");
            }
            return content.asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse SP-04 response: " + e.getMessage(), e);
        }
    }

    /**
     * Parse the assistant {@code content} into an {@link AiGradingResult}.
     * Accepts either a JSON object with {@code score}/{@code feedback} or
     * free text containing a 0-100 number.
     */
    AiGradingResult parseContent(String content, int ceiling) {
        if (content == null || content.isBlank()) {
            // Empty content: zero score, neutral feedback — not an error.
            return new AiGradingResult(0.0, "AI returned empty feedback", 0.5, nowUtc());
        }
        String trimmed = content.trim();
        JsonNode parsed = tryReadJson(trimmed);
        if (parsed != null && parsed.isObject()) {
            double score = clampScore(extractDouble(parsed.path("score")), ceiling);
            String feedback = parsed.path("feedback").isMissingNode()
                    ? trimmed
                    : parsed.path("feedback").asText(trimmed);
            return new AiGradingResult(score, emptyTo(feedback, trimmed), 0.8, nowUtc());
        }
        // Free-text fallback: pull the first 0-100 number, use full text as feedback.
        double score = clampScore(extractScoreFromText(trimmed), ceiling);
        return new AiGradingResult(score, trimmed, 0.6, nowUtc());
    }

    /**
     * Deterministic stub grade for dev/CI. Longer answers score higher,
     * bounded to [40, min(80, ceiling)]. Pure function of answer length so
     * tests are stable.
     */
    AiGradingResult stubGrade(String answer, int ceiling) {
        int len = answer.length();
        int raw = len * 2;
        int lower = Math.min(40, ceiling);
        int upper = Math.min(80, ceiling);
        int score;
        if (upper <= lower) {
            score = lower;
        } else {
            score = Math.min(upper, Math.max(lower, raw));
        }
        String feedback = "Dev mode: AI grading stub. Answer length=" + len;
        return new AiGradingResult((double) score, feedback, 1.0, nowUtc());
    }

    /**
     * EP_DEV_ONLY gate. Active when the env var is set to a non-blank value
     * other than {@code "false"}/{@code "0"} — matches {@link
     * com.ep.grading.config.DevGuardConfig}'s interpretation.
     */
    static boolean isDevOnlyActive(String raw) {
        if (raw == null) {
            return false;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return false;
        }
        return !"false".equalsIgnoreCase(v) && !"0".equals(v);
    }

    /**
     * Resolve the EP_DEV_ONLY flag. Protected so tests can force the dev stub
     * path on/off without mutating the global {@code System.getenv} (which
     * would require junit-pioneer or reflectively hacking the env map).
     */
    protected boolean isDevOnly() {
        return isDevOnlyActive(System.getenv("EP_DEV_ONLY"));
    }

    private String resolveEndpoint() {
        String base = (gatewayUrl == null || gatewayUrl.isBlank())
                ? "http://localhost:8000" : gatewayUrl;
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/v1/chat/completions";
    }

    private String buildRequestBody(String systemPrompt, String userMessage) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode messages = root.putArray("messages");
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userMessage);
            root.put("stream", false);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize SP-04 request: " + e.getMessage(), e);
        }
    }

    private static String safeBodyPreview(String body) {
        if (body == null) {
            return "<no body>";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    private JsonNode tryReadJson(String trimmed) {
        String json = extractJsonObject(trimmed);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isObject() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * If the content embeds a JSON object (possibly wrapped in markdown fences
     * or prose), return the inner object substring; otherwise null.
     */
    private static String extractJsonObject(String trimmed) {
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }

    private static double extractDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return -1;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        String text = node.asText();
        Matcher m = SCORE_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static double extractScoreFromText(String text) {
        Matcher m = SCORE_PATTERN.matcher(text);
        while (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                if (n >= 0 && n <= 100) {
                    return n;
                }
            } catch (NumberFormatException ignored) {
                // try next match
            }
        }
        return 0;
    }

    /**
     * Clamp an extracted score into [0, ceiling]. Missing/invalid scores (-1)
     * collapse to 0 so a malformed LLM reply never over-grades.
     */
    private static double clampScore(double raw, int ceiling) {
        if (raw < 0) {
            return 0;
        }
        if (ceiling <= 0) {
            return 0;
        }
        if (raw > ceiling) {
            return ceiling;
        }
        return raw;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyTo(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(OffsetDateTime.now(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }
}
