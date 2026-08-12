package com.ep.cache;

import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PulsarCacheBroadcast tests - the optional backend activated by
 * {@code ep.cache.broadcast=pulsar}. Mirrors the previous Pulsar CacheBroadcast
 * resilience guarantees: client=null → no-op; producer available → sendAsync;
 * create-failure → no-op, no throw.
 */
class PulsarCacheBroadcastTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishEvictIsNoOpWhenClientNull() {
        ObjectProvider<PulsarClient> nullProvider = mock(ObjectProvider.class);
        when(nullProvider.getIfAvailable()).thenReturn(null);

        PulsarCacheBroadcast b = new PulsarCacheBroadcast(nullProvider, "ep-cache-invalidate");
        b.init();

        assertDoesNotThrow(() -> b.publishEvict("course:detail", "c-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishEvictSendsAsyncWhenProducerAvailable() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class);
        Producer<byte[]> producer = mock(Producer.class);

        when(client.newProducer()).thenReturn(builder);
        when(builder.topic(anyString())).thenReturn(builder);
        when(builder.createAsync()).thenReturn(CompletableFuture.completedFuture(producer));

        ObjectProvider<PulsarClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);

        PulsarCacheBroadcast b = new PulsarCacheBroadcast(provider, "ep-cache-invalidate");
        b.init();

        b.publishEvict("course:detail", "c-2");

        verify(producer, timeout(1000)).sendAsync(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void initSwallowsProducerCreateFailure() {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class);
        when(client.newProducer()).thenReturn(builder);
        when(builder.topic(anyString())).thenReturn(builder);
        CompletableFuture<Producer<byte[]>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(builder.createAsync()).thenReturn(failed);

        ObjectProvider<PulsarClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);

        PulsarCacheBroadcast b = new PulsarCacheBroadcast(provider, "ep-cache-invalidate");
        assertDoesNotThrow(() -> b.init());
        assertDoesNotThrow(() -> b.publishEvict("x", "y"));
    }
}
