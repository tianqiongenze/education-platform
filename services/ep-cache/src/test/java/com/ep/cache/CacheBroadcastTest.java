package com.ep.cache;

import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CacheBroadcastTest {

    /**
     * Degrade path: client=null → init() no-ops → producer stays null → publishEvict is a no-op.
     * This is the resilience guarantee that CacheBroadcast publishes to business callers:
     * "broadcast is best-effort; if Pulsar is unavailable, nothing throws."
     */
    @Test
    void publishEvictIsNoOpWhenClientNull() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PulsarClient> nullProvider = mock(ObjectProvider.class);
        when(nullProvider.getIfAvailable()).thenReturn(null);

        CacheBroadcast b = new CacheBroadcast(nullProvider, "ep-cache-invalidate");
        b.init(); // client null → producer stays null, logs degrade

        assertDoesNotThrow(() -> b.publishEvict("course:detail", "c-1"));
    }

    /**
     * Happy-ish path: client present, producer created via the builder chain, publishEvict
     * invokes sendAsync. No real Pulsar; only the ProducerBuilder.createAsync() future is
     * fulfilled with a mock Producer. Verifies the broadcast code path is exercised.
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishEvictSendsAsyncWhenProducerAvailable() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class);
        Producer<byte[]> producer = mock(Producer.class);

        when(client.newProducer()).thenReturn(builder);
        when(builder.topic(anyString())).thenReturn(builder);
        when(builder.createAsync()).thenReturn(CompletableFuture.completedFuture(producer));

        @SuppressWarnings("unchecked")
        ObjectProvider<PulsarClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);

        CacheBroadcast b = new CacheBroadcast(provider, "ep-cache-invalidate");
        b.init(); // creates the producer via the mock chain

        b.publishEvict("course:detail", "c-2");

        verify(producer, timeout(1000)).sendAsync(any());
    }

    /**
     * Resilience path: producer creation fails (createAsync future completes exceptionally).
     * init() must swallow the exception and leave producer=null; publishEvict stays a no-op.
     */
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

        @SuppressWarnings("unchecked")
        ObjectProvider<PulsarClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);

        CacheBroadcast b = new CacheBroadcast(provider, "ep-cache-invalidate");
        assertDoesNotThrow(() -> b.init());
        assertDoesNotThrow(() -> b.publishEvict("x", "y")); // producer null → no-op
    }
}
