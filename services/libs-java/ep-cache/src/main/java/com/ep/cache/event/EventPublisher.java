package com.ep.cache.event;

import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private final ObjectProvider<PulsarClient> clientProvider;
    private final Map<String, Producer<byte[]>> producers = new ConcurrentHashMap<>();

    public EventPublisher(ObjectProvider<PulsarClient> cp) {
        this.clientProvider = cp;
    }

    public <T> void publish(String topic, T payload) {
        PulsarClient c = clientProvider.getIfAvailable();
        if (c == null) {
            log.debug("no pulsar, skip publish to {}", topic);
            return;
        }
        try {
            Producer<byte[]> p = producers.computeIfAbsent(topic, t -> {
                try {
                    return c.newProducer().topic(t).createAsync().join();
                } catch (Exception e) {
                    log.warn("producer create fail {}: {}", t, e.getMessage());
                    return null;
                }
            });
            if (p != null) {
                p.sendAsync(payload.toString().getBytes())
                        .exceptionally(e -> {
                            log.warn("send fail: {}", e.getMessage());
                            return null;
                        });
            }
        } catch (Exception e) {
            log.warn("publish fail: {}", e.getMessage());
        }
    }
}
