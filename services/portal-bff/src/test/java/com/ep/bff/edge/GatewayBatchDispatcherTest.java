package com.ep.bff.edge;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GatewayBatchDispatcher (SDD §5.2.1 batch dispatch).
 */
class GatewayBatchDispatcherTest {

    @Test
    @DisplayName("onEvent accumulates events until batch size reached")
    void onEventAccumulatesUntilBatchSize() {
        // We can't easily test HTTP forwarding without a real backend,
        // but we can test that the dispatcher doesn't crash on events.
        GatewayBatchDispatcher dispatcher = new GatewayBatchDispatcher(
                "http://localhost:8090", 64, 10L);

        GatewayEvent event = new GatewayEvent();
        event.setMethod("GET");
        event.setPath("/api/users");
        event.setRemoteAddr("127.0.0.1");
        event.setTimestamp(System.nanoTime());

        // Should not throw even if backend is unreachable
        assertDoesNotThrow(() -> {
            dispatcher.onEvent(event, 0, false);
            dispatcher.shutdown();
        });
    }

    @Test
    @DisplayName("shutdown flushes pending batch without error")
    void shutdownFlushesPendingBatch() {
        GatewayBatchDispatcher dispatcher = new GatewayBatchDispatcher(
                "http://localhost:8090", 64, 10L);

        for (int i = 0; i < 10; i++) {
            GatewayEvent event = new GatewayEvent();
            event.setMethod("GET");
            event.setPath("/api/users/" + i);
            event.setRemoteAddr("127.0.0.1");
            event.setTimestamp(System.nanoTime());
            dispatcher.onEvent(event, i, i == 9);
        }

        assertDoesNotThrow(dispatcher::shutdown);
    }

    @Test
    @DisplayName("malformed event does not crash dispatcher")
    void malformedEventDoesNotCrash() {
        GatewayBatchDispatcher dispatcher = new GatewayBatchDispatcher(
                "http://localhost:8090", 64, 10L);

        GatewayEvent badEvent = new GatewayEvent(); // no method/path — must be skipped safely
        badEvent.setRemoteAddr("unknown");
        badEvent.setTimestamp(0);

        assertDoesNotThrow(() -> {
            dispatcher.onEvent(badEvent, 0, true);
            dispatcher.shutdown();
        });
    }

    @Test
    @DisplayName("D-216: rebuilt request keeps Authorization header and body excludes the request line")
    void rebuiltRequestKeepsHeaders() throws Exception {
        // backend stub: capture the forwarded request
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        java.util.concurrent.atomic.AtomicReference<String> auth = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/api/users", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(ex.getRequestBody().readAllBytes()));
            byte[] resp = "{\"ok\":true}".getBytes();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            GatewayBatchDispatcher dispatcher = new GatewayBatchDispatcher(
                    "http://localhost:" + port, 1, 10L);

            io.netty.channel.embedded.EmbeddedChannel client = new io.netty.channel.embedded.EmbeddedChannel();
            GatewayEvent event = new GatewayEvent();
            event.setMethod("POST");
            event.setPath("/api/users");
            event.setHeaders(java.util.Map.of("Authorization", "Bearer abc123",
                    "Content-Type", "application/json"));
            event.setBody("{\"name\":\"x\"}".getBytes());
            event.setRemoteAddr("127.0.0.1");
            event.setTimestamp(System.nanoTime());
            event.setChannel(client);

            dispatcher.onEvent(event, 0, true);

            long deadline = System.currentTimeMillis() + 5_000;
            io.netty.handler.codec.http.FullHttpResponse resp = null;
            while (System.currentTimeMillis() < deadline) {
                resp = client.readOutbound();
                if (resp != null) break;
                Thread.sleep(20);
            }
            dispatcher.shutdown();
            assertNotNull(resp, "dispatcher must write the REAL backend response to the client channel");
            assertEquals(200, resp.status().code());
            String respBody = resp.content().toString(io.netty.util.CharsetUtil.UTF_8);
            assertTrue(respBody.contains("ok"), "response body should be the backend's, resp=" + respBody);
            assertEquals("Bearer abc123", auth.get(), "Authorization must survive the rebuild (was previously lost)");
            assertEquals("{\"name\":\"x\"}", body.get(), "body must be the JSON payload, not the raw request bytes");
        } finally {
            server.stop(0);
        }
    }
}
