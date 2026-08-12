package com.ep.cache.pulsar;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * D-195: Pulsar version abstraction layer.
 * <p>
 * Centralizes all PulsarClient / Producer / Consumer creation so that the
 * version-specific API surface is isolated to a single class. Today this wraps
 * the Pulsar <strong>3.x</strong> client API. When the platform upgrades to
 * Pulsar 4.0 (target: 2027 Q1), only this class needs modification — all
 * callers use the version-neutral factory methods below.
 *
 * <h2>3.x → 4.0 migration notes (documented for readiness)</h2>
 * <ul>
 *   <li><b>Client creation</b>: 3.x uses {@code PulsarClient.builder().serviceUrl(...).build()}.
 *       4.0 deprecates {@code builder()} in favor of {@code PulsarClient.newInstance()} but
 *       keeps {@code builder()} as a deprecated alias — no code change needed in callers.</li>
 *   <li><b>Producer/Consumer builders</b>: 3.x {@code client.newProducer(Schema)} /
 *       {@code client.newConsumer(Schema)}. 4.0 keeps these methods but adds
 *       {@code client.newProducer(Schema).messageRoutingMode(...)} as a new default.
 *       No breaking change for existing callers.</li>
 *   <li><b>Schema registry</b>: 3.x uses the built-in {@code Schema.AUTO_CONSUME()}.
 *       4.0 moves to a standalone schema-registry service — callers using
 *       {@code Schema.BYTES} (our current approach) are unaffected.</li>
 *   <li><b>Geo-replication</b>: 3.x requires global namespace + replicationClusters.
 *       4.0 introduces {@code ReplicationProtocol.v2} — opt-in, not breaking.</li>
 *   <li><b>Client dependency</b>: 3.x {@code pulsar-client} 3.3.9 → 4.0
 *       {@code pulsar-client} 4.x. Version controlled by the unified
 *       {@code ${pulsar.version}} property in each service pom.</li>
 * </ul>
 *
 * <h2>EP_DEV_ONLY</h2>
 * If the PulsarClient bean is unavailable (no Pulsar on classpath or
 * {@code ep.cache.broadcast != pulsar}), all factory methods return {@code null}
 * and log a warning. Callers must null-check.
 */
@Component
@ConditionalOnProperty(name = "ep.cache.broadcast", havingValue = "pulsar")
public class PulsarVersionAdapter {

    private static final Logger log = LoggerFactory.getLogger(PulsarVersionAdapter.class);

    /** Current Pulsar client version — update when upgrading. */
    public static final String CLIENT_VERSION = "3.3.9";
    /** Target upgrade version — informational only. */
    public static final String TARGET_VERSION = "4.0.0";

    private final ObjectProvider<PulsarClient> clientProvider;

    public PulsarVersionAdapter(ObjectProvider<PulsarClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    /**
     * Returns the PulsarClient, or {@code null} if Pulsar is not available
     * (EP_DEV_ONLY).
     */
    public PulsarClient getClient() {
        PulsarClient client = clientProvider.getIfAvailable();
        if (client == null) {
            log.warn("D-195: PulsarClient unavailable (EP_DEV_ONLY) — Pulsar operations will be no-ops");
        }
        return client;
    }

    /**
     * Create a byte[] producer on the given topic.
     *
     * @return the producer, or {@code null} if Pulsar is unavailable or creation fails.
     */
    public Producer<byte[]> createProducer(String topic) {
        PulsarClient client = getClient();
        if (client == null) return null;
        try {
            return client.newProducer()
                    .topic(topic)
                    .sendTimeout(10, TimeUnit.SECONDS)
                    .create();
        } catch (Exception e) {
            log.warn("D-195: createProducer failed for topic={}: {}", topic, e.getMessage());
            return null;
        }
    }

    /**
     * Create a byte[] producer builder (for advanced configuration).
     *
     * @return the builder, or {@code null} if Pulsar is unavailable.
     */
    public ProducerBuilder<byte[]> newProducerBuilder(String topic) {
        PulsarClient client = getClient();
        if (client == null) return null;
        return client.newProducer().topic(topic);
    }

    /**
     * Create a consumer on the given topic + subscription.
     *
     * @return the consumer, or {@code null} if Pulsar is unavailable or creation fails.
     */
    public Consumer<byte[]> createConsumer(String topic, String subscription) {
        PulsarClient client = getClient();
        if (client == null) return null;
        try {
            return client.newConsumer()
                    .topic(topic)
                    .subscriptionName(subscription)
                    .subscriptionType(SubscriptionType.Shared)
                    .subscribe();
        } catch (Exception e) {
            log.warn("D-195: createConsumer failed for topic={}/{}: {}", topic, subscription, e.getMessage());
            return null;
        }
    }

    /**
     * Create a consumer builder (for advanced configuration).
     *
     * @return the builder, or {@code null} if Pulsar is unavailable.
     */
    public ConsumerBuilder<byte[]> newConsumerBuilder(String topic, String subscription) {
        PulsarClient client = getClient();
        if (client == null) return null;
        return client.newConsumer()
                .topic(topic)
                .subscriptionName(subscription);
    }

    /**
     * Create a typed-schema producer (for structured messages).
     *
     * @param schema the Pulsar schema (e.g. {@link Schema#BYTES}, {@link Schema#STRING})
     * @return the producer, or {@code null} if Pulsar is unavailable or creation fails.
     */
    public <T> Producer<T> createTypedProducer(String topic, Schema<T> schema) {
        PulsarClient client = getClient();
        if (client == null) return null;
        try {
            return client.newProducer(schema)
                    .topic(topic)
                    .sendTimeout(10, TimeUnit.SECONDS)
                    .create();
        } catch (Exception e) {
            log.warn("D-195: createTypedProducer failed for topic={}: {}", topic, e.getMessage());
            return null;
        }
    }

    /**
     * Create a typed-schema consumer.
     *
     * @param schema the Pulsar schema
     * @return the consumer, or {@code null} if Pulsar is unavailable or creation fails.
     */
    public <T> Consumer<T> createTypedConsumer(String topic, String subscription, Schema<T> schema) {
        PulsarClient client = getClient();
        if (client == null) return null;
        try {
            return client.newConsumer(schema)
                    .topic(topic)
                    .subscriptionName(subscription)
                    .subscriptionType(SubscriptionType.Shared)
                    .subscribe();
        } catch (Exception e) {
            log.warn("D-195: createTypedConsumer failed for topic={}/{}: {}", topic, subscription, e.getMessage());
            return null;
        }
    }

    /**
     * Close a producer/consumer silently (EP_DEV_ONLY safe).
     */
    public void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("D-195: close quietly: {}", e.getMessage());
        }
    }

    /**
     * @return the current Pulsar client version string (for readiness checks).
     */
    public String getClientVersion() {
        return CLIENT_VERSION;
    }

    /**
     * @return true if a PulsarClient is available, false otherwise.
     */
    public boolean isAvailable() {
        return getClient() != null;
    }
}
