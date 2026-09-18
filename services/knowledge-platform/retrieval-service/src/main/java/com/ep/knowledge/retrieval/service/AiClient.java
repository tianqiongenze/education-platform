package com.ep.knowledge.retrieval.service;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Minimal client for the AI Gateway chat-completion endpoint, used by
 * {@link RagService} to generate the final answer over retrieved context.
 *
 * <p>Mirrors {@code data-platform}'s {@code AiGatewayClient}: JDK built-in
 * {@link HttpClient} (Java 21) and Jackson {@link ObjectMapper} — zero new
 * Maven dependencies (D-074/D-075). GraalVM AOT-friendly: no reflection, no
 * synchronized blocks (D-039/D-040).
 *
 * <p>The AI gateway exposes the chat-completion contract at
 * {@code POST /v1/chat/completions}. Request body:
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
 *   "choices": [{"message": {"role": "assistant", "content": "..."}}]
 * }
 * }</pre>
 *
 * <p>EP_DEV_ONLY guard: when {@code EP_DEV_ONLY} is unset, blank, or
 * {@code "true"} (the dev/CI default), the client skips the HTTP call entirely
 * and returns a contextual stub answer built from the user message. This keeps
 * the retrieval pipeline fully functional in dev/CI without a live AI gateway,
 * consistent with the EP_DEV_ONLY guard pattern used across EP.
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o";
    private static final double DEFAULT_TEMPERATURE = 0.0;

    private final String gatewayUrl;
    private final String model;
    private final int timeoutSec;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    /**
     * Spring-managed constructor. Resolves the AI gateway URL from
     * {@code ai.gateway-url} (default {@code http://localhost:8000}).
     *
     * @param gatewayUrl AI gateway base URL (no path)
     * @param model      LLM model id
     * @param timeoutSec request timeout in seconds
     */
    public AiClient(@Value("${ai.gateway-url:http://localhost:8000}") String gatewayUrl,
                    @Value("${ai.model:gpt-4o}") String model,
                    @Value("${ai.timeout-sec:10}") int timeoutSec) {
        this(gatewayUrl, model, timeoutSec, new ObjectMapper(), buildHttpClient(timeoutSec));
    }

    /**
     * Test-friendly constructor with all collaborators.
     */
    AiClient(String gatewayUrl, String model, int timeoutSec,
             ObjectMapper mapper, HttpClient httpClient) {
        this.gatewayUrl = gatewayUrl;
        this.model = model;
        this.timeoutSec = timeoutSec;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    /**
     * Send a chat-completion request to the AI gateway and return the
     * generated text.
     *
     * <p>In dev/CI (EP_DEV_ONLY guard active), the HTTP call is skipped and a
     * contextual stub answer derived from {@code userMessage} is returned.
     *
     * @param systemPrompt system-role prompt (retrieved context framing)
     * @param userMessage  user-role message (the RAG query)
     * @return generated answer text, or empty string on error/null inputs
     */
    public String chat(String systemPrompt, String userMessage) {
        if (systemPrompt == null || userMessage == null) {
            log.warn("[AiClient] Null input — systemPrompt={}, userMessage={}",
                    systemPrompt == null, userMessage == null);
            return "";
        }

        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);
        if (devGuard) {
            String stub = devStubAnswer(userMessage);
            log.info("[AiClient] EP_DEV_ONLY active — skipping HTTP, returning stub answer");
            return stub;
        }

        try {
            String body = mapper.writeValueAsString(Map.of(
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userMessage)
                    ),
                    "model", model,
                    "temperature", DEFAULT_TEMPERATURE
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayUrl + CHAT_COMPLETIONS_PATH))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer anonymous")
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.warn("[AiClient] HTTP {} from AI gateway — body={}", status,
                        truncate(response.body()));
                return "";
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("[AiClient] AI gateway response has no choices — body={}", truncate(response.body()));
                return "";
            }
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                log.warn("[AiClient] AI gateway response has no content — body={}", truncate(response.body()));
                return "";
            }
            return content.asText();
        } catch (HttpTimeoutException e) {
            log.warn("[AiClient] HTTP timeout after {}s calling AI gateway: {}",
                    timeoutSec, e.getMessage());
            return "";
        } catch (Exception e) {
            log.warn("[AiClient] Error calling AI gateway: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Build a contextual stub answer for dev/CI (no live LLM).
     *
     * @param userMessage the RAG query
     * @return a deterministic stub answer referencing the query
     */
    static String devStubAnswer(String userMessage) {
        return "Based on retrieved context, the answer to '" + userMessage
                + "' requires LLM integration (dev mode stub).";
    }

    private static HttpClient buildHttpClient(int timeoutSec) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(timeoutSec, 5)))
                .build();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
