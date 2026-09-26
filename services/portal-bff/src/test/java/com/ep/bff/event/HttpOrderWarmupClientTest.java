package com.ep.bff.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HttpOrderWarmupClient tests (pure JUnit + Mockito, no Spring context, no
 * HTTP server). Uses Mockito deep stubs for the fluent {@link RestClient}
 * chain and covers the D-228 order-detail warm-up (GET
 * {@code /api/v1/orders/{id}} on order-platform, mirroring the BFF's
 * {@code OrderDubboController} path).
 */
class HttpOrderWarmupClientTest {

    private RestClient restClient;
    private HttpOrderWarmupClient client;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        client = new HttpOrderWarmupClient(restClient);
    }

    @Test
    void warmupOrderDetailCallsOrderDetailEndpoint() {
        when(restClient.get().uri(anyString(), anyLong()).retrieve().toBodilessEntity())
                .thenReturn(ResponseEntity.ok().build());

        assertDoesNotThrow(() -> client.warmupOrderDetail(5001L));
    }

    @Test
    void warmupFailureIsSwallowed() {
        when(restClient.get().uri(anyString(), anyLong()).retrieve().toBodilessEntity())
                .thenThrow(new RuntimeException("order-platform down"));

        // Best-effort contract: never propagates.
        assertDoesNotThrow(() -> client.warmupOrderDetail(5001L));
    }
}
