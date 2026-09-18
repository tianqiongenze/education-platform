package com.ep.business.event;

import com.ep.business.common.DomainEvent;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PulsarEventPublisher tests. Mirrors the resilience contract documented on the
 * publisher: disabled -> no-op; enabled + client -> sendAsync; any failure ->
 * caught and logged, never thrown. No real Pulsar; mocks only.
 */
class PulsarEventPublisherTest {

    private static PulsarClientProperties props() {
        PulsarClientProperties p = new PulsarClientProperties();
        p.getTopic().setDomainEvents("persistent://public/default/test-events");
        return p;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PulsarClient> clientProvider(PulsarClient client) {
        ObjectProvider<PulsarClient> cp = mock(ObjectProvider.class);
        when(cp.getIfAvailable()).thenReturn(client);
        return cp;
    }

    private static DomainEvent sampleEvent() {
        return new DomainEvent() {
            @Override
            public long getAggregateId() {
                return 42L;
            }

            @Override
            public Instant getOccurredAt() {
                return Instant.parse("2026-08-06T12:00:00Z");
            }

            @Override
            public String toString() {
                return "SampleEvent{aggregateId=42}";
            }
        };
    }

    @Test
    void publishIsNoOpWhenDisabled() {
        ObjectProvider<PulsarClient> cp = clientProvider(null);
        PulsarEventPublisher pub = new PulsarEventPublisher(cp, props(), false);
        pub.init();

        // Must not throw and must not touch Pulsar.
        assertDoesNotThrow(() -> pub.publish(sampleEvent()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishIsNoOpWhenEnabledButClientMissing() {
        ObjectProvider<PulsarClient> cp = clientProvider(null);
        PulsarEventPublisher pub = new PulsarEventPublisher(cp, props(), true);
        pub.init();

        // Enabled, but no PulsarClient bean -> degrade to no-op, no throw.
        assertDoesNotThrow(() -> pub.publish(sampleEvent()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishIsNoOpWhenProducerCreateFails() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class);
        when(client.newProducer()).thenReturn(builder);
        when(builder.topic(anyString())).thenReturn(builder);
        when(builder.blockIfQueueFull(anyBoolean())).thenReturn(builder);
        when(builder.sendTimeout(anyInt(), any())).thenReturn(builder);
        when(builder.create()).thenThrow(new RuntimeException("create fail"));

        PulsarEventPublisher pub = new PulsarEventPublisher(clientProvider(client), props(), true);
        pub.init();

        // Producer creation failed -> publish is a no-op, no throw.
        assertDoesNotThrow(() -> pub.publish(sampleEvent()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishSendsAsyncWhenEnabledAndProducerReady() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class);
        Producer<byte[]> producer = mock(Producer.class);
        when(client.newProducer()).thenReturn(builder);
        when(builder.topic(anyString())).thenReturn(builder);
        when(builder.blockIfQueueFull(anyBoolean())).thenReturn(builder);
        when(builder.sendTimeout(anyInt(), any())).thenReturn(builder);
        when(builder.create()).thenReturn(producer);
        when(producer.sendAsync(any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        PulsarEventPublisher pub = new PulsarEventPublisher(clientProvider(client), props(), true);
        pub.init();

        pub.publish(sampleEvent());

        verify(producer, timeout(1000)).sendAsync(any(byte[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishFailureIsCaughtAndDoesNotThrow() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class);
        Producer<byte[]> producer = mock(Producer.class);
        when(client.newProducer()).thenReturn(builder);
        when(builder.topic(anyString())).thenReturn(builder);
        when(builder.blockIfQueueFull(anyBoolean())).thenReturn(builder);
        when(builder.sendTimeout(anyInt(), any())).thenReturn(builder);
        when(builder.create()).thenReturn(producer);
        // Simulate an exception thrown synchronously during serialization/send path.
        when(producer.sendAsync(any(byte[].class)))
                .thenThrow(new RuntimeException("send boom"));

        PulsarEventPublisher pub = new PulsarEventPublisher(clientProvider(client), props(), true);
        pub.init();

        // Must not propagate the send failure.
        assertDoesNotThrow(() -> pub.publish(sampleEvent()));
    }

    @Test
    void publishNullEventIsNoOp() {
        ObjectProvider<PulsarClient> cp = clientProvider(null);
        PulsarEventPublisher pub = new PulsarEventPublisher(cp, props(), true);
        pub.init();

        assertDoesNotThrow(() -> pub.publish(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishRetriesSendFailuresAndSucceedsOnThirdAttempt() throws Exception {
        // D-event-reliability: sendAsync fails twice, third attempt succeeds —
        // the publisher must retry (bounded, backoff) instead of dropping.
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class);
        Producer<byte[]> producer = mock(Producer.class);
        when(client.newProducer()).thenReturn(builder);
        when(builder.topic(anyString())).thenReturn(builder);
        when(builder.blockIfQueueFull(anyBoolean())).thenReturn(builder);
        when(builder.sendTimeout(anyInt(), any())).thenReturn(builder);
        when(builder.create()).thenReturn(producer);

        CompletableFuture<org.apache.pulsar.client.api.MessageId> fail1 = new CompletableFuture<>();
        fail1.completeExceptionally(new RuntimeException("fail 1"));
        CompletableFuture<org.apache.pulsar.client.api.MessageId> fail2 = new CompletableFuture<>();
        fail2.completeExceptionally(new RuntimeException("fail 2"));
        when(producer.sendAsync(any(byte[].class)))
                .thenReturn(fail1, fail2, CompletableFuture.completedFuture(null));

        // 5-arg constructor: 2 retries with a 1ms backoff keeps the test fast.
        PulsarEventPublisher pub = new PulsarEventPublisher(clientProvider(client), props(), true, 2, 1L);
        pub.init();

        assertDoesNotThrow(() -> pub.publish(sampleEvent()));

        verify(producer, timeout(2000).times(3)).sendAsync(any(byte[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishDropsEventAfterExhaustedRetriesWithoutThrowing() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class);
        Producer<byte[]> producer = mock(Producer.class);
        when(client.newProducer()).thenReturn(builder);
        when(builder.topic(anyString())).thenReturn(builder);
        when(builder.blockIfQueueFull(anyBoolean())).thenReturn(builder);
        when(builder.sendTimeout(anyInt(), any())).thenReturn(builder);
        when(builder.create()).thenReturn(producer);
        CompletableFuture<org.apache.pulsar.client.api.MessageId> alwaysFail = new CompletableFuture<>();
        alwaysFail.completeExceptionally(new RuntimeException("broker down"));
        when(producer.sendAsync(any(byte[].class))).thenReturn(alwaysFail);

        PulsarEventPublisher pub = new PulsarEventPublisher(clientProvider(client), props(), true, 2, 1L);
        pub.init();

        assertDoesNotThrow(() -> pub.publish(sampleEvent()));

        // Initial attempt + 2 retries, then the event is dropped (WARN-logged).
        verify(producer, timeout(2000).times(3)).sendAsync(any(byte[].class));
    }
}
