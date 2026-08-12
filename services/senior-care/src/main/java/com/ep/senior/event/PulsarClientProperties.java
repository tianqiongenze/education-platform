package com.ep.senior.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D-147: Configuration properties for the senior-care Pulsar event publisher.
 * Bound to the {@code ep.pulsar.*} namespace.
 */
@ConfigurationProperties(prefix = "ep.pulsar")
public class PulsarClientProperties {

    private String serviceUrl = "pulsar://localhost:6650";
    private Topic topic = new Topic();

    public String getServiceUrl() { return serviceUrl; }
    public void setServiceUrl(String serviceUrl) { this.serviceUrl = serviceUrl; }
    public Topic getTopic() { return topic; }
    public void setTopic(Topic topic) { this.topic = topic; }

    public static class Topic {
        private String domainEvents = "persistent://public/default/senior-care-events";

        public String getDomainEvents() { return domainEvents; }
        public void setDomainEvents(String domainEvents) { this.domainEvents = domainEvents; }
    }
}
