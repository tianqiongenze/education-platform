package com.ep.data.common;

import java.io.Serializable;
import java.util.Objects;

/**
 * Configuration for a Pulsar CDC producer. Built by {@link PulsarSinkFactory};
 * does NOT open a Pulsar connection.
 */
public class PulsarProducerConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final long DEFAULT_SEND_TIMEOUT_MS = 3000L;
    public static final boolean DEFAULT_BLOCK_IF_QUEUE_FULL = true;
    public static final int DEFAULT_MAX_PENDING_MESSAGES = 1000;
    public static final String DEFAULT_SCHEMA_TYPE = "AVRO";

    private String topic;
    private String schemaType;
    private long sendTimeoutMs = DEFAULT_SEND_TIMEOUT_MS;
    private boolean blockIfQueueFull = DEFAULT_BLOCK_IF_QUEUE_FULL;
    private int maxPendingMessages = DEFAULT_MAX_PENDING_MESSAGES;

    public PulsarProducerConfig() {
    }

    public PulsarProducerConfig(String topic, String schemaType, long sendTimeoutMs,
                                boolean blockIfQueueFull, int maxPendingMessages) {
        this.topic = topic;
        this.schemaType = schemaType;
        this.sendTimeoutMs = sendTimeoutMs;
        this.blockIfQueueFull = blockIfQueueFull;
        this.maxPendingMessages = maxPendingMessages;
    }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getSchemaType() { return schemaType; }
    public void setSchemaType(String schemaType) { this.schemaType = schemaType; }

    public long getSendTimeoutMs() { return sendTimeoutMs; }
    public void setSendTimeoutMs(long sendTimeoutMs) { this.sendTimeoutMs = sendTimeoutMs; }

    public boolean isBlockIfQueueFull() { return blockIfQueueFull; }
    public void setBlockIfQueueFull(boolean blockIfQueueFull) { this.blockIfQueueFull = blockIfQueueFull; }

    public int getMaxPendingMessages() { return maxPendingMessages; }
    public void setMaxPendingMessages(int maxPendingMessages) { this.maxPendingMessages = maxPendingMessages; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PulsarProducerConfig that)) return false;
        return sendTimeoutMs == that.sendTimeoutMs
                && blockIfQueueFull == that.blockIfQueueFull
                && maxPendingMessages == that.maxPendingMessages
                && Objects.equals(topic, that.topic)
                && Objects.equals(schemaType, that.schemaType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topic, schemaType, sendTimeoutMs, blockIfQueueFull, maxPendingMessages);
    }

    @Override
    public String toString() {
        return "PulsarProducerConfig{topic='" + topic + "', schemaType='" + schemaType
                + "', sendTimeoutMs=" + sendTimeoutMs
                + ", blockIfQueueFull=" + blockIfQueueFull
                + ", maxPendingMessages=" + maxPendingMessages + '}';
    }
}
