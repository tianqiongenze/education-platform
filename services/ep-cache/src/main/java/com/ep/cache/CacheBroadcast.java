package com.ep.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class CacheBroadcast {
    private static final Logger log = LoggerFactory.getLogger(CacheBroadcast.class);
    private final ObjectProvider<PulsarClient> clientProvider;
    private final String topic;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Producer<byte[]> producer;

    public CacheBroadcast(ObjectProvider<PulsarClient> cp, String topic) {
        this.clientProvider = cp;
        this.topic = topic;
    }

    @PostConstruct
    public void init() {
        PulsarClient client = clientProvider.getIfAvailable();
        if (client == null) {
            log.info("Pulsar client null, CacheBroadcast degrade to no-op");
            return;
        }
        try {
            producer = client.newProducer().topic(topic).createAsync().join();
        } catch (Exception e) {
            log.warn("Pulsar producer create fail: {}", e.getMessage());
        }
    }

    public void publishEvict(String cacheName, String key) {
        if (producer == null) {
            log.debug("no producer, skip broadcast");
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
