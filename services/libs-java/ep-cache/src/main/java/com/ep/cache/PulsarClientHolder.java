package com.ep.cache;

import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.PulsarClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Holds an optional {@link PulsarClient} for the Pulsar broadcast backend.
 * <p>
 * P1-5 (audit D-048): now {@code @ConditionalOnProperty} so the bean only
 * exists when {@code ep.cache.broadcast=pulsar}. This prevents a useless
 * (and potentially class-path-failing) Pulsar client from being instantiated
 * for the default Redis Pub/Sub mode, satisfying the iron rule
 * "禁止声明依赖但未使用".
 */
@Component
@ConditionalOnProperty(name = "ep.cache.broadcast", havingValue = "pulsar")
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
