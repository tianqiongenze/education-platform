package com.ep.bff.edge;

import com.ep.disruptor.PublishResult;
import org.junit.jupiter.api.*;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GatewayRingBuffer (SDD §5.2.1).
 *
 * <p>Note: GatewayRingBuffer is @ConditionalOnProperty(ep.gateway.netty.enabled=true),
 * so we test it directly by instantiating and calling @PostConstruct manually.
 */
class GatewayRingBufferTest {

    private GatewayRingBuffer ringBuffer;

    @BeforeEach
    void setup() throws Exception {
        ringBuffer = new GatewayRingBuffer();
        setField(ringBuffer, "bufferSize", 256);
        setField(ringBuffer, "batchSize", 16);
        setField(ringBuffer, "batchTimeoutMs", 100L);
        setField(ringBuffer, "backendUrl", "http://localhost:8090");
        ringBuffer.init();
    }

    @AfterEach
    void teardown() {
        if (ringBuffer != null) {
            ringBuffer.shutdown();
        }
    }

    @Test
    @DisplayName("publish returns PUBLISHED when RingBuffer has capacity")
    void publishReturnsPublishedWhenCapacityAvailable() {
        GatewayEvent event = makeEvent("GET /api/users HTTP/1.1\r\n\r\n");
        PublishResult result = ringBuffer.publish(event);
        assertEquals(PublishResult.PUBLISHED, result);
    }

    @Test
    @DisplayName("stats reports correct buffer size")
    void statsReportsCorrectBufferSize() {
        GatewayEvent event = makeEvent("GET /healthz HTTP/1.1\r\n\r\n");
        ringBuffer.publish(event);
        var stats = ringBuffer.stats();
        assertEquals("gateway-ingress", stats.name());
        assertEquals(256L, stats.bufferSize());
        assertTrue(stats.published() >= 1);
    }

    @Test
    @DisplayName("shutdown completes without hanging")
    void shutdownCompletesWithoutHanging() {
        for (int i = 0; i < 50; i++) {
            ringBuffer.publish(makeEvent("GET /api/users/" + i + " HTTP/1.1\r\n\r\n"));
        }
        ringBuffer.shutdown();
        // If we reach here, shutdown completed
        assertTrue(true);
    }

    private GatewayEvent makeEvent(String rawRequest) {
        GatewayEvent event = new GatewayEvent();
        event.setRawBytes(rawRequest.getBytes());
        event.setRemoteAddr("127.0.0.1");
        event.setTimestamp(System.nanoTime());
        return event;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
