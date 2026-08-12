package com.ep.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Optional Pulsar-backed {@link CacheBroadcast}.
 * <p>
 * P1-5 (audit D-048): kept as an alternative when a deployment already runs
 * Pulsar and prefers it over Redis Pub/Sub for broadcast. Activated only when
 * {@code ep.cache.broadcast=pulsar} (see {@link CacheBroadcastConfig}).
 * <p>
 * Resilience contract: if the {@link PulsarClient} is unavailable or producer
 * creation fails, all calls degrade to no-ops without throwing.
 */
public class PulsarCacheBroadcast implements CacheBroadcast {
    private static final Logger log = LoggerFactory.getLogger(PulsarCacheBroadcast.class);

    private final ObjectProvider<PulsarClient> clientProvider;
    private final String topic;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private volatile Producer<byte[]> producer;

    public PulsarCacheBroadcast(ObjectProvider<PulsarClient> clientProvider, String topic) {
        this.clientProvider = clientProvider;
        this.topic = topic;
    }

    @PostConstruct
    public void init() {
        PulsarClient client = clientProvider.getIfAvailable();
        if (client == null) {
            log.info("Pulsar client null, PulsarCacheBroadcast degrade to no-op");
            return;
        }
        try {
            producer = client.newProducer().topic(topic).createAsync().join();
        } catch (Exception e) {
            log.warn("Pulsar producer create fail: {}", e.getMessage());
        }
    }

    @Override
    public void publishEvict(String cacheName, String key) {
        if (producer == null) {
            log.debug("no pulsar producer, skip broadcast");
            return;
        }
        try {
            producer.sendAsync(mapper.writeValueAsBytes(new InvalidateMsg(cacheName, key)))
                    .exceptionally(e -> {
                        log.warn("broadcast send fail: {}", e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("broadcast serialize/send fail: {}", e.getMessage());
        }
    }
}
