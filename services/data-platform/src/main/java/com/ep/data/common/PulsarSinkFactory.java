package com.ep.data.common;

/**
 * Builds {@link PulsarProducerConfig} instances for publishing CDC events.
 *
 * <p>D-016/D-028: NO Kafka — Pulsar is the only messaging bus. C-07: AVRO is
 * the default wire format. This factory only assembles configuration and does
 * NOT connect to Pulsar.
 */
public final class PulsarSinkFactory {

    private PulsarSinkFactory() {
    }

    /**
     * Create a producer config for a CDC topic.
     *
     * @param topic      full Pulsar topic name
     * @param schemaType wire schema type; null/blank defaults to {@code AVRO}
     * @return a populated {@link PulsarProducerConfig}
     */
    public static PulsarProducerConfig createProducerConfig(String topic, String schemaType) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        String resolvedSchema = (schemaType == null || schemaType.isBlank())
                ? PulsarProducerConfig.DEFAULT_SCHEMA_TYPE
                : schemaType.toUpperCase();
        return new PulsarProducerConfig(topic, resolvedSchema,
                PulsarProducerConfig.DEFAULT_SEND_TIMEOUT_MS,
                PulsarProducerConfig.DEFAULT_BLOCK_IF_QUEUE_FULL,
                PulsarProducerConfig.DEFAULT_MAX_PENDING_MESSAGES);
    }
}
