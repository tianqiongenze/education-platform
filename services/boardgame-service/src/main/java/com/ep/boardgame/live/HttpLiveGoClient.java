package com.ep.boardgame.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * D-192: Default {@link LiveGoClient} using the JDK {@link HttpClient}.
 * <p>
 * Talks to the live-go service at {@code http://live-go:8090}. When live-go is
 * unreachable (EP_DEV_ONLY), it logs a warning and returns {@code null} /
 * {@code false} rather than throwing, so the boardgame-service keeps working
 * in dev/test without the live-go sidecar.
 */
@Component
public class HttpLiveGoClient implements LiveGoClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLiveGoClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${ep.live-go.base-url:http://live-go:8090}")
    private String baseUrl;

    @Override
    public LiveRoomInfo createBroadcastRoom(Long gameRoomId) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "sourceId", String.valueOf(gameRoomId),
                    "mode", "BROADCAST"));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/live/rooms"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JsonNode node = mapper.readTree(resp.body());
                String roomId = node.path("roomId").asText(null);
                String ingest = node.path("ingestUrl").asText(null);
                String play = node.path("playUrl").asText(null);
                if (ingest != null && play != null) {
                    return new LiveRoomInfo(roomId, ingest, play);
                }
            }
            log.warn("D-192: live-go create room returned status {}: {}", resp.statusCode(), resp.body());
        } catch (Exception e) {
            log.warn("D-192: live-go not reachable (EP_DEV_ONLY): {}", e.getMessage());
        }
        return null;
    }

    @Override
    public boolean stopBroadcastRoom(Long gameRoomId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/live/rooms/" + gameRoomId))
                    .DELETE()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            log.warn("D-192: live-go stop not reachable (EP_DEV_ONLY): {}", e.getMessage());
            return false;
        }
    }
}
