package com.ep.cache;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.SubscriptionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PulsarCacheEvictionConsumer tests.
 *
 * Verifies:
 *  - EP_DEV_ONLY: null PulsarClient → start() is a no-op, no throw.
 *  - Subscribe failure (broker down) → no throw, consumer stays null.
 *  - A received JSON {@link InvalidateMsg} message is parsed and the matching
 *    L1 entry is evicted via the wrapped {@link CacheEvictionListener}.
 *  - The per-instance subscription name is unique per call.
 *
 * No real Pulsar broker is required.
 */
class PulsarCacheEvictionConsumerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    @SuppressWarnings("unchecked")
    void startIsNoOpWhenPulsarClientUnavailable() {
        ObjectProvider<PulsarClient> nullProvider = mock(ObjectProvider.class);
        when(nullProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<CacheManager> cmProvider = mock(ObjectProvider.class);

        PulsarCacheEvictionConsumer c =
                new PulsarCacheEvictionConsumer(nullProvider, cmProvider, "ep-cache-invalidate");
        assertDoesNotThrow(c::start);
        assertDoesNotThrow(c::stop);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startSwallowsSubscribeFailure() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ConsumerBuilder<byte[]> builder = mock(ConsumerBuilder.class);
        when(client.newConsumer()).thenReturn(builder);
        when(builder.topic(anyString())).thenReturn(builder);
        when(builder.subscriptionName(anyString())).thenReturn(builder);
        when(builder.subscriptionType(any(SubscriptionType.class))).thenReturn(builder);
        when(builder.messageListener(any(MessageListener.class))).thenReturn(builder);
        when(builder.subscribe()).thenThrow(new RuntimeException("broker down"));

        ObjectProvider<PulsarClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        ObjectProvider<CacheManager> cmProvider = mock(ObjectProvider.class);

        PulsarCacheEvictionConsumer c =
                new PulsarCacheEvictionConsumer(provider, cmProvider, "ep-cache-invalidate");
        assertDoesNotThrow(c::start);   // EP_DEV_ONLY: must not throw
        assertDoesNotThrow(c::stop);
    }

    @Test
    @SuppressWarnings("unchecked")
    void messageEvictsL1Entry() throws Exception {
        // Prepare a real L1 cache with one entry that should be evicted.
        Cache cache = new ConcurrentMapCache("course:detail");
        cache.put("c-1", "v1");
        assertNotNull(cache.get("c-1"));

        CacheManager cm = mock(CacheManager.class);
        when(cm.getCache("course:detail")).thenReturn(cache);
        ObjectProvider<CacheManager> cmProvider = mock(ObjectProvider.class);
        when(cmProvider.getIfAvailable()).thenReturn(cm);

        ObjectProvider<PulsarClient> clientProvider = mock(ObjectProvider.class);
        PulsarCacheEvictionConsumer consumer =
                new PulsarCacheEvictionConsumer(clientProvider, cmProvider, "ep-cache-invalidate");

        // Build a Pulsar Message whose data is the JSON InvalidateMsg payload.
        byte[] payload = MAPPER.writeValueAsBytes(new InvalidateMsg("course:detail", "c-1"));
        Message<byte[]> msg = mock(Message.class);
        when(msg.getData()).thenReturn(payload);

        // A mock consumer whose acknowledgeAsync is a no-op.
        Consumer<byte[]> pulsarConsumer = mock(Consumer.class);
        when(pulsarConsumer.acknowledgeAsync(any(Message.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        // Drive the handler directly — this is what the MessageListener invokes.
        consumer.handleMessage(pulsarConsumer, msg);

        // L1 entry must be gone.
        assertNull(cache.get("c-1"), "L1 entry should be evicted by Pulsar broadcast");

        // Message must be acknowledged even on success path.
        verify(pulsarConsumer).acknowledgeAsync(msg);
    }

    @Test
    @SuppressWarnings("unchecked")
    void messageAcknowledgedEvenWhenPayloadIsGarbage() {
        ObjectProvider<CacheManager> cmProvider = mock(ObjectProvider.class);
        ObjectProvider<PulsarClient> clientProvider = mock(ObjectProvider.class);

        PulsarCacheEvictionConsumer consumer =
                new PulsarCacheEvictionConsumer(clientProvider, cmProvider, "ep-cache-invalidate");

        Message<byte[]> msg = mock(Message.class);
        when(msg.getData()).thenReturn("not-json".getBytes());
        Consumer<byte[]> pulsarConsumer = mock(Consumer.class);
        when(pulsarConsumer.acknowledgeAsync(any(Message.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        assertDoesNotThrow(() -> consumer.handleMessage(pulsarConsumer, msg));
        // Poison pill must still be acked so it cannot block the subscription.
        verify(pulsarConsumer).acknowledgeAsync(msg);
    }

    @Test
    void uniqueSubscriptionNameIsUniqueAndPrefixed() {
        String a = PulsarCacheEvictionConsumer.uniqueSubscriptionName();
        String b = PulsarCacheEvictionConsumer.uniqueSubscriptionName();
        assertTrue(a.startsWith("ep-cache-invalidate-"), "subscription prefix: " + a);
        assertNotEquals(a, b, "subscription names must be per-instance unique");
    }
}
