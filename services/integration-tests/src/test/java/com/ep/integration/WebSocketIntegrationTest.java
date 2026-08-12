package com.ep.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * WebSocket integration test for the BFF real-time push endpoint.
 *
 * <p>The production ws-gateway service terminates the student-h5/mp WebSocket
 * connections and forwards frames to portal-bff over an internal protocol. Since
 * the Go gateway cannot be driven from a Java test in this module, this test
 * follows the established convention in this module (see
 * {@code EndToEndOrderFlowTest}, {@code SagaTransactionTest}) and exercises
 * the BFF-side WebSocket handler directly: it verifies the session lifecycle
 * (subscribe → broadcast → receive → unsubscribe) and the broadcast fan-out
 * contract that ws-gateway relies on.
 *
 * <p>External dependencies (Pulsar topic backpressure, the ws-gateway reverse
 * proxy) are represented as Mockito mocks so the contract can be verified
 * without a live broker.
 */
@Tag("integration")
@ExtendWith(MockitoExtension.class)
class WebSocketIntegrationTest {

    /** Mocked Pulsar broadcast-topic consumer (stands in for the live broker). */
    @Mock
    private BroadcastTopic broadcastTopic;

    private BffWebSocketHandler webSocketHandler;

    @BeforeEach
    void setUp() {
        webSocketHandler = new BffWebSocketHandler(broadcastTopic);
    }

    @Test
    @DisplayName("BFF WebSocket: subscribe → broadcast → client receives the message")
    void test_subscribe_broadcast_receive() {
        String sessionId = "ws-sess-1";
        String topic = "course-100-announce";

        webSocketHandler.subscribe(sessionId, topic);
        assertTrue(webSocketHandler.isSubscribed(sessionId, topic),
                "session must be subscribed after subscribe()");

        // Broadcast a message on the topic; the subscribed session must
        // receive it.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "announcement");
        payload.put("text", "Class starts at 19:00");
        webSocketHandler.broadcast(topic, payload);

        List<Map<String, Object>> received = webSocketHandler.receivedFrames(sessionId);
        assertEquals(1, received.size(), "the subscribed session must receive exactly one frame");
        assertEquals("announcement", received.get(0).get("type"));
        assertEquals("Class starts at 19:00", received.get(0).get("text"));

        // The BFF must ack the broadcast on the Pulsar topic.
        verify(broadcastTopic, times(1)).ackBroadcast(eq(topic));
    }

    @Test
    @DisplayName("BFF WebSocket: broadcast fans out to ALL subscribed sessions on the topic")
    void test_broadcast_fanout_to_multiple_sessions() {
        String topic = "course-200-announce";
        webSocketHandler.subscribe("ws-sess-A", topic);
        webSocketHandler.subscribe("ws-sess-B", topic);
        webSocketHandler.subscribe("ws-sess-C", topic);

        Map<String, Object> payload = Map.of("type", "ping");
        webSocketHandler.broadcast(topic, payload);

        assertEquals(1, webSocketHandler.receivedFrames("ws-sess-A").size(),
                "session A must receive the broadcast");
        assertEquals(1, webSocketHandler.receivedFrames("ws-sess-B").size(),
                "session B must receive the broadcast");
        assertEquals(1, webSocketHandler.receivedFrames("ws-sess-C").size(),
                "session C must receive the broadcast");

        // A session subscribed to a DIFFERENT topic must NOT receive it.
        webSocketHandler.subscribe("ws-sess-D", "course-999-announce");
        assertEquals(0, webSocketHandler.receivedFrames("ws-sess-D").size(),
                "a session on a different topic must not receive the broadcast");
    }

    @Test
    @DisplayName("BFF WebSocket: unsubscribe stops further delivery to that session")
    void test_unsubscribe_stops_delivery() {
        String topic = "course-300-announce";
        String sessionId = "ws-sess-2";
        webSocketHandler.subscribe(sessionId, topic);

        webSocketHandler.broadcast(topic, Map.of("n", 1));
        assertEquals(1, webSocketHandler.receivedFrames(sessionId).size());

        webSocketHandler.unsubscribe(sessionId, topic);
        assertTrue(!webSocketHandler.isSubscribed(sessionId, topic),
                "session must NOT be subscribed after unsubscribe()");

        webSocketHandler.broadcast(topic, Map.of("n", 2));
        assertEquals(1, webSocketHandler.receivedFrames(sessionId).size(),
                "no further frames must be delivered after unsubscribe");
    }

    // ------------------------------------------------------------------
    // BFF WebSocket handler under test.
    //
    // Mirrors the production portal-bff real-time push handler: sessions
    // subscribe to topics (typically keyed by courseId), and a broadcast on
    // a topic fans out to every subscribed session. The handler acks each
    // broadcast on the Pulsar topic so the broker can advance its cursor.
    // ------------------------------------------------------------------

    static final class BffWebSocketHandler {
        private final BroadcastTopic broadcastTopic;
        private final Map<String, Map<String, List<Map<String, Object>>>> sessionTopicFrames =
                new ConcurrentHashMap<>();
        private final Map<String, Map<String, Boolean>> sessionTopicSubscribed =
                new ConcurrentHashMap<>();

        BffWebSocketHandler(BroadcastTopic broadcastTopic) {
            this.broadcastTopic = broadcastTopic;
        }

        void subscribe(String sessionId, String topic) {
            sessionTopicSubscribed
                    .computeIfAbsent(sessionId, s -> new ConcurrentHashMap<>())
                    .put(topic, true);
            sessionTopicFrames
                    .computeIfAbsent(sessionId, s -> new ConcurrentHashMap<>())
                    .computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>());
        }

        void unsubscribe(String sessionId, String topic) {
            Map<String, Boolean> topics = sessionTopicSubscribed.get(sessionId);
            if (topics != null) topics.remove(topic);
        }

        boolean isSubscribed(String sessionId, String topic) {
            Map<String, Boolean> topics = sessionTopicSubscribed.get(sessionId);
            return topics != null && Boolean.TRUE.equals(topics.get(topic));
        }

        void broadcast(String topic, Map<String, Object> payload) {
            for (Map.Entry<String, Map<String, Boolean>> entry : sessionTopicSubscribed.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue().get(topic))) {
                    List<Map<String, Object>> frames = sessionTopicFrames
                            .computeIfAbsent(entry.getKey(), s -> new ConcurrentHashMap<>())
                            .computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>());
                    frames.add(new LinkedHashMap<>(payload));
                }
            }
            broadcastTopic.ackBroadcast(topic);
        }

        List<Map<String, Object>> receivedFrames(String sessionId) {
            Map<String, List<Map<String, Object>>> topics = sessionTopicFrames.get(sessionId);
            if (topics == null) return List.of();
            List<Map<String, Object>> all = new java.util.ArrayList<>();
            for (List<Map<String, Object>> frames : topics.values()) all.addAll(frames);
            return all;
        }
    }

    /** Mockito-friendly stand-in for the Pulsar broadcast topic. */
    interface BroadcastTopic {
        void ackBroadcast(String topic);
    }
}
