package com.ep.cache.pulsar;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PulsarVersionAdapterTest {

    private ObjectProvider<PulsarClient> provider;
    private PulsarClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        provider = mock(ObjectProvider.class);
        client = mock(PulsarClient.class);
    }

    @Test
    void getClient_returnsNull_whenPulsarUnavailable() {
        when(provider.getIfAvailable()).thenReturn(null);
        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);

        assertNull(adapter.getClient());
        assertFalse(adapter.isAvailable());
    }

    @Test
    void getClient_returnsClient_whenAvailable() {
        when(provider.getIfAvailable()).thenReturn(client);
        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);

        assertNotNull(adapter.getClient());
        assertTrue(adapter.isAvailable());
    }

    @Test
    void createProducer_returnsNull_whenClientUnavailable() {
        when(provider.getIfAvailable()).thenReturn(null);
        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);

        assertNull(adapter.createProducer("test-topic"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createProducer_returnsProducer_whenOk() throws Exception {
        when(provider.getIfAvailable()).thenReturn(client);
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class);
        Producer<byte[]> producer = mock(Producer.class);
        when(client.newProducer()).thenReturn(builder);
        when(builder.topic(any(String.class))).thenReturn(builder);
        when(builder.sendTimeout(anyInt(), any())).thenReturn(builder);
        when(builder.create()).thenReturn(producer);

        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);
        Producer<byte[]> result = adapter.createProducer("topic-1");

        assertNotNull(result);
        assertEquals(producer, result);
    }

    @Test
    void createProducer_returnsNull_whenCreateThrows() throws Exception {
        when(provider.getIfAvailable()).thenReturn(client);
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class);
        when(client.newProducer()).thenReturn(builder);
        when(builder.topic(any(String.class))).thenReturn(builder);
        when(builder.sendTimeout(anyInt(), any())).thenReturn(builder);
        when(builder.create()).thenThrow(new RuntimeException("broker down"));

        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);
        assertNull(adapter.createProducer("topic-1"));
    }

    @Test
    void createConsumer_returnsNull_whenClientUnavailable() {
        when(provider.getIfAvailable()).thenReturn(null);
        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);

        assertNull(adapter.createConsumer("t", "sub"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createConsumer_returnsConsumer_whenOk() throws Exception {
        when(provider.getIfAvailable()).thenReturn(client);
        ConsumerBuilder<byte[]> builder = mock(ConsumerBuilder.class);
        Consumer<byte[]> consumer = mock(Consumer.class);
        when(client.newConsumer()).thenReturn(builder);
        when(builder.topic(any(String.class))).thenReturn(builder);
        when(builder.subscriptionName(any(String.class))).thenReturn(builder);
        when(builder.subscriptionType(any())).thenReturn(builder);
        when(builder.subscribe()).thenReturn(consumer);

        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);
        Consumer<byte[]> result = adapter.createConsumer("t", "sub");

        assertNotNull(result);
    }

    @Test
    void newProducerBuilder_returnsNull_whenClientUnavailable() {
        when(provider.getIfAvailable()).thenReturn(null);
        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);

        assertNull(adapter.newProducerBuilder("t"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void newProducerBuilder_returnsBuilder_whenOk() {
        when(provider.getIfAvailable()).thenReturn(client);
        ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class);
        when(client.newProducer()).thenReturn(builder);
        when(builder.topic(any(String.class))).thenReturn(builder);

        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);
        assertNotNull(adapter.newProducerBuilder("t"));
    }

    @Test
    void newConsumerBuilder_returnsNull_whenClientUnavailable() {
        when(provider.getIfAvailable()).thenReturn(null);
        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);

        assertNull(adapter.newConsumerBuilder("t", "sub"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void newConsumerBuilder_returnsBuilder_whenOk() {
        when(provider.getIfAvailable()).thenReturn(client);
        ConsumerBuilder<byte[]> builder = mock(ConsumerBuilder.class);
        when(client.newConsumer()).thenReturn(builder);
        when(builder.topic(any(String.class))).thenReturn(builder);
        when(builder.subscriptionName(any(String.class))).thenReturn(builder);

        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);
        assertNotNull(adapter.newConsumerBuilder("t", "sub"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createTypedProducer_returnsProducer_whenOk() throws Exception {
        when(provider.getIfAvailable()).thenReturn(client);
        ProducerBuilder<String> builder = mock(ProducerBuilder.class);
        Producer<String> producer = mock(Producer.class);
        when(client.newProducer(Schema.STRING)).thenReturn(builder);
        when(builder.topic(any(String.class))).thenReturn(builder);
        when(builder.sendTimeout(anyInt(), any())).thenReturn(builder);
        when(builder.create()).thenReturn(producer);

        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);
        Producer<String> result = adapter.createTypedProducer("t", Schema.STRING);

        assertNotNull(result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createTypedConsumer_returnsConsumer_whenOk() throws Exception {
        when(provider.getIfAvailable()).thenReturn(client);
        ConsumerBuilder<String> builder = mock(ConsumerBuilder.class);
        Consumer<String> consumer = mock(Consumer.class);
        when(client.newConsumer(Schema.STRING)).thenReturn(builder);
        when(builder.topic(any(String.class))).thenReturn(builder);
        when(builder.subscriptionName(any(String.class))).thenReturn(builder);
        when(builder.subscriptionType(any())).thenReturn(builder);
        when(builder.subscribe()).thenReturn(consumer);

        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);
        Consumer<String> result = adapter.createTypedConsumer("t", "sub", Schema.STRING);

        assertNotNull(result);
    }

    @Test
    void createTypedProducer_returnsNull_whenClientUnavailable() {
        when(provider.getIfAvailable()).thenReturn(null);
        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);

        assertNull(adapter.createTypedProducer("t", Schema.STRING));
    }

    @Test
    void createTypedConsumer_returnsNull_whenClientUnavailable() {
        when(provider.getIfAvailable()).thenReturn(null);
        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);

        assertNull(adapter.createTypedConsumer("t", "sub", Schema.STRING));
    }

    @Test
    void closeQuietly_handlesNull() {
        when(provider.getIfAvailable()).thenReturn(null);
        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);

        // Should not throw
        adapter.closeQuietly(null);
    }

    @Test
    void closeQuietly_swallowsExceptions() throws Exception {
        when(provider.getIfAvailable()).thenReturn(null);
        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);
        AutoCloseable throwing = () -> { throw new RuntimeException("close failed"); };

        // Should not throw
        adapter.closeQuietly(throwing);
    }

    @Test
    void getClientVersion_returns3_3_9() {
        when(provider.getIfAvailable()).thenReturn(null);
        PulsarVersionAdapter adapter = new PulsarVersionAdapter(provider);

        assertEquals("3.3.9", adapter.getClientVersion());
    }
}
