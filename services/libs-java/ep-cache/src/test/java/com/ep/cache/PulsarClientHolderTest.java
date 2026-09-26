package com.ep.cache;

import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PulsarClientHolder lifecycle tests: lazy get + cache, close, and exception swallowing.
 * No real Pulsar; the client is a mock.
 */
class PulsarClientHolderTest {

    @Test
    @SuppressWarnings("unchecked")
    void getReturnsAndCachesClientFromProvider() {
        PulsarClient client = mock(PulsarClient.class);
        ObjectProvider<PulsarClient> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(client);

        PulsarClientHolder h = new PulsarClientHolder(p);
        assertSame(client, h.get());
        // second get() must NOT re-query the provider (cached)
        h.get();
        verify(p, times(1)).getIfAvailable();
    }

    @Test
    @SuppressWarnings("unchecked")
    void closeClosesClient() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ObjectProvider<PulsarClient> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(client);

        PulsarClientHolder h = new PulsarClientHolder(p);
        h.get();
        h.close();
        verify(client).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void closeToleratesException() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ObjectProvider<PulsarClient> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(client);
        doThrow(new RuntimeException("close boom")).when(client).close();

        PulsarClientHolder h = new PulsarClientHolder(p);
        h.get();
        assertDoesNotThrow(h::close);
    }

    @Test
    @SuppressWarnings("unchecked")
    void closeIsNoOpWhenClientNeverFetched() {
        ObjectProvider<PulsarClient> p = mock(ObjectProvider.class);
        PulsarClientHolder h = new PulsarClientHolder(p);
        assertDoesNotThrow(h::close); // client==null → no close() call
    }

    @Test
    @SuppressWarnings("unchecked")
    void getReturnsNullWhenProviderHasNothing() {
        ObjectProvider<PulsarClient> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        PulsarClientHolder h = new PulsarClientHolder(p);
        assertNull(h.get());
    }
}
