package com.ep.memory;

import com.ep.memory.model.ContextWindow;
import com.ep.memory.model.Memory;
import com.ep.memory.model.MemoryAddRequest;
import com.ep.memory.model.MemorySearchRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP fallback client for the memory service.
 *
 * <p>Used when {@code ep.memory.use-rpc=false} or when the Dubbo registry is
 * unavailable. Targets the SP-04 AI Gateway REST surface
 * ({@code /v1/memory/*}).</p>
 *
 * <p>Uses {@link java.net.http.HttpClient} which is virtual-thread friendly
 * (D-039). All JSON (de)serialization goes through Jackson to remain
 * reflection-free for D-040 GraalVM native images.</p>
 */
public class MemoryClient {

    private static final Logger log = LoggerFactory.getLogger(MemoryClient.class);
    private static final TypeReference<List<Memory>> MEMORY_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> MAP_SS = new TypeReference<>() {};

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final int timeoutSec;

    public MemoryClient(String baseUrl, int timeoutSec, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://ai-platform-gateway:8000" : baseUrl;
        this.timeoutSec = timeoutSec <= 0 ? 10 : timeoutSec;
        this.objectMapper = objectMapper;
        // Virtual-thread executor keeps blocking I/O off platform carriers (D-039).
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.timeoutSec))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public void addMemory(MemoryAddRequest request) {
        post("/v1/memory/add", request);
    }

    public List<Memory> searchMemory(MemorySearchRequest request) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/memory/search"))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(request)))
                .build();
        return sendAndParse(req, MEMORY_LIST);
    }

    public ContextWindow getContext(String tenantId, String userId, String sessionId) {
        String path = String.format("/v1/memory/context?tenantId=%s&userId=%s&sessionId=%s",
                enc(tenantId), enc(userId), enc(sessionId));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Accept", "application/json")
                .GET()
                .build();
        return sendAndParse(req, ContextWindow.class);
    }

    public void forgetMemory(String memoryId) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/memory/" + enc(memoryId)))
                .timeout(Duration.ofSeconds(timeoutSec))
                .DELETE()
                .build();
        send(req);
    }

    public void promoteToOrg(String memoryId) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/memory/" + enc(memoryId) + "/promote"))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        send(req);
    }

    // ---- internals ----

    private void post(String path, Object body) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                .build();
        send(req);
    }

    private void send(HttpRequest req) {
        try {
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 400) {
                log.warn("Memory HTTP {} -> status {}", req.uri(), resp.statusCode());
            }
        } catch (Exception e) {
            log.warn("Memory HTTP {} failed: {}", req.uri(), e.toString());
        }
    }

    private <T> T sendAndParse(HttpRequest req, Class<T> type) {
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("Memory HTTP {} -> status {}", req.uri(), resp.statusCode());
                return null;
            }
            return objectMapper.readValue(resp.body(), type);
        } catch (Exception e) {
            log.warn("Memory HTTP {} failed: {}", req.uri(), e.toString());
            return null;
        }
    }

    private <T> T sendAndParse(HttpRequest req, TypeReference<T> type) {
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("Memory HTTP {} -> status {}", req.uri(), resp.statusCode());
                return null;
            }
            return objectMapper.readValue(resp.body(), type);
        } catch (Exception e) {
            log.warn("Memory HTTP {} failed: {}", req.uri(), e.toString());
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize memory payload", e);
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
