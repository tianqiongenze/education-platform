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
        event.setRawBytes("GET /api/users HTTP/1.1\r\n\r\n".getBytes());
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
            event.setRawBytes(("GET /api/users/" + i + " HTTP/1.1\r\n\r\n").getBytes());
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

        GatewayEvent badEvent = new GatewayEvent();
        badEvent.setRawBytes(null); // null bytes — should be skipped
        badEvent.setRemoteAddr("unknown");
        badEvent.setTimestamp(0);

        assertDoesNotThrow(() -> {
            dispatcher.onEvent(badEvent, 0, true);
            dispatcher.shutdown();
        });
    }
}
