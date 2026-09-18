package com.ep.data.nlquery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Minimal client for the AI Gateway chat-completion endpoint
 * (ADR-D08, §12.4.1).
 *
 * <p>Uses the JDK built-in {@link HttpClient} (Java 21) and Jackson
 * {@link ObjectMapper} — zero new Maven dependencies per D-074/D-075.
 *
 * <p>The actual AI Gateway implementation exposes the chat-completion contract at
 * {@code POST /v1/chat/completions} (this differs from the path documented in
 * SDD §12.7; the implementation is authoritative). Request body:
 * <pre>{@code
 * {
 *   "messages": [
 *     {"role": "system", "content": "..."},
 *     {"role": "user", "content": "..."}
 *   ],
 *   "model": "gpt-4o",
 *   "temperature": 0.0
 * }
 * }</pre>
 *
 * <p>Response body (extracted field: {@code choices[0].message.content}):
 * <pre>{@code
 * {
 *   "id": "...",
 *   "model": "...",
 *   "choices": [{"index": 0, "message": {"role": "assistant", "content": "..."}, "finish_reason": "stop"}],
 *   "usage": {...}
 * }
 * }</pre>
 *
 * <p>EP_DEV_ONLY guard: when {@code EP_DEV_ONLY} is unset, blank, or
 * {@code "true"}, the client skips the HTTP call entirely and returns a
 * template SQL derived from keywords in the user question. This makes the
 * module fully functional in dev/CI without a live AI gateway.
 */
@Component
public class AiGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayClient.class);

    /** Path appended to {@link NlQueryConfig#gatewayUrl()} at call time. */
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final NlQueryConfig config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    /**
     * Construct with configuration resolved from the environment.
     */
    public AiGatewayClient() {
        this(NlQueryConfig.fromEnv());
    }

    /**
     * Construct with an explicit configuration (testable).
     *
     * @param config AI gateway + LLM configuration
     */
    public AiGatewayClient(NlQueryConfig config) {
        this(config, new ObjectMapper().registerModule(new JavaTimeModule()), buildHttpClient(config));
    }

    /**
     * Construct with all collaborators (for tests).
     *
     * @param config     AI gateway + LLM configuration
     * @param mapper     Jackson ObjectMapper
     * @param httpClient JDK HttpClient
     */
    public AiGatewayClient(NlQueryConfig config, ObjectMapper mapper, HttpClient httpClient) {
        this.config = config;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    /**
     * Send a chat-completion request to the AI Gateway and return the
     * generated text.
     *
     * <p>In dev/CI (EP_DEV_ONLY guard active), the HTTP call is skipped and a
     * template SQL is returned based on keywords in {@code userMessage}:
     * <ul>
     *   <li>contains {@code "count"} or {@code "多少"} →
     *       {@code SELECT COUNT(*) FROM default_table}</li>
     *   <li>contains {@code "list"} or {@code "列出"} →
     *       {@code SELECT * FROM default_table LIMIT 100}</li>
     *   <li>otherwise → {@code SELECT * FROM default_table LIMIT 10}</li>
     * </ul>
     *
     * @param systemPrompt system-role prompt
     * @param userMessage  user-role message (the NL question)
     * @return generated text, or empty string on error/timeout/null inputs
     */
    public String chatCompletion(String systemPrompt, String userMessage) {
        if (systemPrompt == null || userMessage == null) {
            log.warn("[AiGatewayClient] Null input — systemPrompt={}, userMessage={}",
                    systemPrompt == null, userMessage == null);
            return "";
        }

        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);
        if (devGuard) {
            String template = devTemplateSql(userMessage);
            log.info("[AiGatewayClient] EP_DEV_ONLY active — skipping HTTP, returning template SQL");
            return template;
        }

        try {
            String body = mapper.writeValueAsString(Map.of(
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userMessage)
                    ),
                    "model", config.model(),
                    "temperature", config.temperature()
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.gatewayUrl() + CHAT_COMPLETIONS_PATH))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer anonymous")
                    .timeout(Duration.ofSeconds(config.timeoutSec()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.warn("[AiGatewayClient] HTTP {} from AI gateway — body={}", status,
                        truncate(response.body()));
                return "";
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("[AiGatewayClient] AI gateway response has no choices — body={}", truncate(response.body()));
                return "";
            }
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                log.warn("[AiGatewayClient] AI gateway response has no content — body={}", truncate(response.body()));
                return "";
            }
            return content.asText();
        } catch (HttpTimeoutException e) {
            log.warn("[AiGatewayClient] HTTP timeout after {}s calling AI gateway: {}",
                    config.timeoutSec(), e.getMessage());
            return "";
        } catch (Exception e) {
            log.warn("[AiGatewayClient] Error calling AI gateway: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Derive a template SQL from keywords in the user question (dev/CI only).
     *
     * @param question user question
     * @return template SQL string
     */
    static String devTemplateSql(String question) {
        if (question == null || question.isBlank()) {
            return "SELECT * FROM default_table LIMIT 10";
        }
        String lower = question.toLowerCase();
        if (lower.contains("count") || question.contains("多少")) {
            return "SELECT COUNT(*) FROM default_table";
        }
        if (lower.contains("list") || question.contains("列出")) {
            return "SELECT * FROM default_table LIMIT 100";
        }
        return "SELECT * FROM default_table LIMIT 10";
    }

    private static HttpClient buildHttpClient(NlQueryConfig config) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(config.timeoutSec(), 5)))
                .build();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
