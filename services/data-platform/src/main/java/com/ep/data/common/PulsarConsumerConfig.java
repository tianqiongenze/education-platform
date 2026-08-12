package com.ep.data.common;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable-ish configuration for a Pulsar CDC consumer. Built by
 * {@link PulsarSourceFactory}; does NOT open a Pulsar connection.
 */
public class PulsarConsumerConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Default receive timeout (ms). */
    public static final int DEFAULT_RECEIVE_TIMEOUT_MS = 5000;
    /** C-07: Avro is the mandatory wire format. */
    public static final String DEFAULT_SCHEMA_TYPE = "AVRO";

    private String topic;
    private String subscription;
    private String schemaType;
    private int receiveTimeoutMs = DEFAULT_RECEIVE_TIMEOUT_MS;
    private Map<String, String> properties = new LinkedHashMap<>();

    public PulsarConsumerConfig() {
    }

    public PulsarConsumerConfig(String topic, String subscription, String schemaType,
                                int receiveTimeoutMs, Map<String, String> properties) {
        this.topic = topic;
        this.subscription = subscription;
        this.schemaType = schemaType;
        this.receiveTimeoutMs = receiveTimeoutMs;
        this.properties = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
    }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getSubscription() { return subscription; }
    public void setSubscription(String subscription) { this.subscription = subscription; }

    public String getSchemaType() { return schemaType; }
    public void setSchemaType(String schemaType) { this.schemaType = schemaType; }

    public int getReceiveTimeoutMs() { return receiveTimeoutMs; }
    public void setReceiveTimeoutMs(int receiveTimeoutMs) { this.receiveTimeoutMs = receiveTimeoutMs; }

    public Map<String, String> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PulsarConsumerConfig that)) return false;
        return receiveTimeoutMs == that.receiveTimeoutMs
                && Objects.equals(topic, that.topic)
                && Objects.equals(subscription, that.subscription)
                && Objects.equals(schemaType, that.schemaType)
                && Objects.equals(properties, that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topic, subscription, schemaType, receiveTimeoutMs, properties);
    }

    @Override
    public String toString() {
        return "PulsarConsumerConfig{topic='" + topic + "', subscription='" + subscription
                + "', schemaType='" + schemaType + "', receiveTimeoutMs=" + receiveTimeoutMs
                + ", properties=" + properties + '}';
    }
}
