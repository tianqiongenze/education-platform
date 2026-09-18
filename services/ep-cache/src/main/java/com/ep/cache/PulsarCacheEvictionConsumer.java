package com.ep.cache;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Pulsar consumer for cache-eviction notifications.
 * <p>
 * Subscribes to the {@code ep-cache-invalidate} topic and, on each message,
 * parses the JSON {@link InvalidateMsg} payload and clears the corresponding
 * local L1 (Caffeine) entry via {@link CacheEvictionListener}.
 *
 * <h2>Subscription model</h2>
 * Each pod/node must receive EVERY eviction message (a miss would leave a stale
 * L1 entry). We therefore use a per-instance unique subscription name of the
 * form {@code ep-cache-invalidate-<hostname>-<random>} with
 * {@link SubscriptionType#Failover} is NOT used; instead each instance gets its
 * own exclusive subscription so Pulsar fans out every message to every
 * subscriber. (With a unique subscription name, the subscription type is
 * effectively irrelevant — each subscription independently consumes the full
 * topic. We use {@link SubscriptionType#Shared} for forward compatibility with
 * horizontal scaling of a single subscription, but the unique name guarantees
 * full fan-out per pod.)
 *
 * <h2>EP_DEV_ONLY</h2>
 * If the {@link PulsarClient} bean is unavailable (no Pulsar on classpath or
 * Pulsar offline — typical for local dev), {@link #start()} logs at debug level
 * and returns without creating a consumer. No exception is thrown; the cache
 * continues to work as L1+L2 with no cross-node invalidation. This mirrors the
 * resilience pattern in {@link PulsarCacheBroadcast} and {@code PulsarVersionAdapter}.
 */
public class PulsarCacheEvictionConsumer {
    private static final Logger log = LoggerFactory.getLogger(PulsarCacheEvictionConsumer.class);

    private final ObjectProvider<PulsarClient> clientProvider;
    private final ObjectProvider<org.springframework.cache.CacheManager> cmProvider;
    private final String topic;
    private final CacheEvictionListener listener;

    private volatile Consumer<byte[]> consumer;

    public PulsarCacheEvictionConsumer(
            ObjectProvider<PulsarClient> clientProvider,
            ObjectProvider<org.springframework.cache.CacheManager> cmProvider,
            String topic) {
        this.clientProvider = clientProvider;
        this.cmProvider = cmProvider;
        this.topic = topic;
        this.listener = new CacheEvictionListener(cmProvider);
    }

    /**
     * Start the Pulsar consumer. EP_DEV_ONLY safe: if the PulsarClient is
     * unavailable or subscription fails, logs at debug/warn and returns without
     * throwing. The consumer uses an async {@link MessageListener} so message
     * processing does not block the Pulsar client internal thread pool.
     */
    @PostConstruct
    public void start() {
        PulsarClient client = clientProvider.getIfAvailable();
        if (client == null) {
            log.debug("Pulsar client null, PulsarCacheEvictionConsumer degrade to no-op (EP_DEV_ONLY)");
            return;
        }
        String subscription = uniqueSubscriptionName();
        try {
            ConsumerBuilder<byte[]> builder = client.newConsumer()
                    .topic(topic)
                    .subscriptionName(subscription)
                    .subscriptionType(SubscriptionType.Shared)
                    .messageListener(this::onMessage);
            consumer = builder.subscribe();
            log.info("PulsarCacheEvictionConsumer subscribed topic={} subscription={}", topic, subscription);
        } catch (Exception e) {
            // EP_DEV_ONLY: broker down, auth failed, etc. — degrade silently.
            log.debug("Pulsar consumer subscribe fail (EP_DEV_ONLY), topic={}: {}", topic, e.getMessage());
            consumer = null;
        }
    }

    /**
     * Build a per-instance unique subscription name so every pod independently
     * consumes the full topic (full fan-out). Format:
     * {@code ep-cache-invalidate-<hostname>-<random>}.
     */
    static String uniqueSubscriptionName() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return "ep-cache-invalidate-" + host + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt());
    }

    /**
     * Pulsar {@link MessageListener} callback. Parses the JSON payload and
     * delegates to {@link CacheEvictionListener#onMessage(byte[])} which clears
     * the local L1 entry. Always acknowledges the message so a poison pill
     * cannot block the subscription.
     */
    private void onMessage(Consumer<byte[]> c, Message<byte[]> msg) {
        handleMessage(c, msg);
    }

    /**
     * Package-private entry point for the {@link MessageListener} callback and
     * for unit tests. Parses the JSON payload and delegates to
     * {@link CacheEvictionListener#onMessage(byte[])} which clears the local L1
     * entry. Always acknowledges the message so a poison pill cannot block the
     * subscription.
     */
    void handleMessage(Consumer<byte[]> c, Message<byte[]> msg) {
        try {
            if (msg != null && msg.getData() != null) {
                listener.onMessage(msg.getData());
            }
        } catch (Exception e) {
            log.warn("pulsar eviction message handling fail: {}", e.getMessage());
        } finally {
            try {
                if (c != null && msg != null) {
                    c.acknowledgeAsync(msg);
                }
            } catch (Exception e) {
                log.debug("pulsar ack fail: {}", e.getMessage());
            }
        }
    }

    /**
     * Close the consumer on shutdown. Best-effort; never throws.
     */
    @PreDestroy
    public void stop() {
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                log.debug("pulsar consumer close fail: {}", e.getMessage());
            }
        }
    }

    // Exposed for tests.
    CacheEvictionListener listener() {
        return listener;
    }
}
