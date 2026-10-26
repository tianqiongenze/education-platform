package com.ep.web.common.outbox;

/**
 * D-234: broker-adapter port implemented by each host service. The relay calls
 * it to forward persisted outbox rows to the actual message broker (Pulsar).
 *
 * <p>Keeping this as a port (not a Pulsar import) lets ep-web-common stay
 * broker-free and satisfies the ArchUnit slice rule: hosts own the
 * {@code infrastructure → outbox} dependency, never the reverse.</p>
 */
public interface OutboxPublisherPort {

    /**
     * Publish a persisted row to the broker.
     *
     * @param eventType  concrete event type name (routing key)
     * @param payloadJson the JSON payload persisted in the outbox row
     * @throws Exception on broker failure — the relay counts a retry and keeps
     *                   the row unpublished
     */
    void publishRaw(String eventType, String payloadJson) throws Exception;
}
