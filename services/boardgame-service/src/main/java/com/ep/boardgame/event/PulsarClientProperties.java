package com.ep.boardgame.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the boardgame-service Pulsar event publisher.
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
        private String domainEvents = "persistent://public/default/boardgame-service-events";

        public String getDomainEvents() { return domainEvents; }
        public void setDomainEvents(String domainEvents) { this.domainEvents = domainEvents; }
    }
}
