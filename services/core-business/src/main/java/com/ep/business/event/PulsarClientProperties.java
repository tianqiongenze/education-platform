package com.ep.business.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the core-business Pulsar event publisher.
 * Bound to the {@code ep.pulsar.*} namespace.
 */
@ConfigurationProperties(prefix = "ep.pulsar")
public class PulsarClientProperties {

    /** Pulsar broker service URL, e.g. {@code pulsar://localhost:6650}. */
    private String serviceUrl = "pulsar://localhost:6650";

    /** Topic used to publish core-business domain events. */
    private Topic topic = new Topic();

    public String getServiceUrl() {
        return serviceUrl;
    }

    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    public Topic getTopic() {
        return topic;
    }

    public void setTopic(Topic topic) {
        this.topic = topic;
    }

    public static class Topic {
        private String domainEvents = "persistent://public/default/ep-business-domain-events";

        public String getDomainEvents() {
            return domainEvents;
        }

        public void setDomainEvents(String domainEvents) {
            this.domainEvents = domainEvents;
        }
    }
}
