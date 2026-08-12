package com.ep.data.common;

/**
 * Builds {@link PulsarConsumerConfig} instances for CDC topics.
 *
 * <p>D-016/D-028: Pulsar is the unified messaging infra (NO Kafka).
 * C-07: Avro + Schema Registry is mandatory; the default schema type is AVRO.
 * This factory only assembles configuration — it does NOT connect to Pulsar.
 */
public final class PulsarSourceFactory {

    /** Namespace prefix for CDC topics (D-028: ns=cdc://). */
    public static final String CDC_TOPIC_PREFIX = "persistent://public/cdc/";

    private PulsarSourceFactory() {
    }

    /**
     * Create a consumer config for a CDC topic.
     *
     * @param topic        full Pulsar topic name (e.g. {@code persistent://public/cdc/order_group})
     * @param subscription subscription name (shared/failover determined elsewhere)
     * @param schemaType   wire schema type; null/blank defaults to {@code AVRO}
     * @return a populated {@link PulsarConsumerConfig}
     */
    public static PulsarConsumerConfig createConsumerConfig(String topic, String subscription, String schemaType) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (subscription == null || subscription.isBlank()) {
            throw new IllegalArgumentException("subscription must not be blank");
        }
        String resolvedSchema = (schemaType == null || schemaType.isBlank())
                ? PulsarConsumerConfig.DEFAULT_SCHEMA_TYPE
                : schemaType.toUpperCase();
        return new PulsarConsumerConfig(topic, subscription, resolvedSchema,
                PulsarConsumerConfig.DEFAULT_RECEIVE_TIMEOUT_MS, null);
    }
}
