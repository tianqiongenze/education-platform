package com.ep.cache.event;

import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * EventPublisher tests. Mirrors CacheBroadcast's resilience guarantees:
 * null-client → no-op; producer-available → sendAsync; create-failure → no-op, no throw.
 * No real Pulsar; mocks only.
 */
class EventPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishIsNoOpWhenClientNull() {
        ObjectProvider<PulsarClient> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        EventPublisher pub = new EventPublisher(p);

        assertDoesNotThrow(() -> pub.publish("any-topic", "payload"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishSendsAsyncWhenClientAndProducerAvailable() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class);
        Producer<byte[]> producer = mock(Producer.class);
        when(client.newProducer()).thenReturn(builder);
        when(builder.topic(anyString())).thenReturn(builder);
        when(builder.createAsync()).thenReturn(CompletableFuture.completedFuture(producer));

        ObjectProvider<PulsarClient> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(client);

        EventPublisher pub = new EventPublisher(p);
        pub.publish("topic-a", "hello");

        verify(producer, timeout(1000)).sendAsync("hello".getBytes());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishIsNoOpWhenProducerCreateFails() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class);
        when(client.newProducer()).thenReturn(builder);
        when(builder.topic(anyString())).thenReturn(builder);
        CompletableFuture<Producer<byte[]>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("create fail"));
        when(builder.createAsync()).thenReturn(failed);

        ObjectProvider<PulsarClient> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(client);

        EventPublisher pub = new EventPublisher(p);
        // producer creation fails inside computeIfAbsent; publish must not throw.
        assertDoesNotThrow(() -> pub.publish("topic-b", "payload"));
    }

    @Test
    void eventTopicsHoldsCacheInvalidateConstant() {
        assertEquals("ep-cache-invalidate", EventTopics.CACHE_INVALIDATE);
    }
}
