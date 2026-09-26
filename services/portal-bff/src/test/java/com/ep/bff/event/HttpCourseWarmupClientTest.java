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
 * HttpCourseWarmupClient tests (pure JUnit + Mockito, no Spring context, no
 * HTTP server). Uses Mockito deep stubs for the fluent
 * {@link RestClient} chain and covers the D-228 {@code evictCourseDetail}
 * capability (evict-as-refresh GET, see implementation javadoc).
 */
class HttpCourseWarmupClientTest {

    private RestClient restClient;
    private HttpCourseWarmupClient client;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        client = new HttpCourseWarmupClient(restClient);
    }

    @Test
    void evictCourseDetailCallsCourseDetailEndpoint() {
        when(restClient.get().uri(anyString(), anyLong()).retrieve().toBodilessEntity())
                .thenReturn(ResponseEntity.ok().build());

        assertDoesNotThrow(() -> client.evictCourseDetail(42L));
    }

    @Test
    void warmupCourseDetailCallsCourseDetailEndpoint() {
        when(restClient.get().uri(anyString(), anyLong()).retrieve().toBodilessEntity())
                .thenReturn(ResponseEntity.ok().build());

        assertDoesNotThrow(() -> client.warmupCourseDetail(7L));
    }

    @Test
    void evictFailureIsSwallowed() {
        when(restClient.get().uri(anyString(), anyLong()).retrieve().toBodilessEntity())
                .thenThrow(new RuntimeException("core-business down"));

        // Best-effort contract: never propagates.
        assertDoesNotThrow(() -> client.evictCourseDetail(42L));
    }
}
