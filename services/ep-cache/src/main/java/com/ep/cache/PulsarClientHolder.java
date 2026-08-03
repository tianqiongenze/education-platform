package com.ep.cache;

import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.PulsarClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class PulsarClientHolder {
    private static final Logger log = LoggerFactory.getLogger(PulsarClientHolder.class);
    private final ObjectProvider<PulsarClient> provider;
    private volatile PulsarClient client;

    public PulsarClientHolder(ObjectProvider<PulsarClient> p) {
        this.provider = p;
    }

    public PulsarClient get() {
        if (client == null) {
            client = provider.getIfAvailable();
        }
        return client;
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("pulsar close: {}", e.getMessage());
            }
        }
    }
}
