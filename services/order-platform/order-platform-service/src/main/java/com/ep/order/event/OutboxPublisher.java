package com.ep.order.event;

/**
 * D-217: Outbox publisher port — the strategy the {@link OutboxRelayService}
 * uses to forward outbox rows to the messaging backbone.
 * <p>
 * Defined in the {@code event} package (not {@code infrastructure}) so the
 * relay's dependency stays within its own slice, keeping the ArchUnit cycle
 * rule satisfied ({@code infrastructure → event} only, never back). The
 * concrete adapter {@code PulsarOutboxPublisherAdapter} lives in
 * {@code infrastructure} and delegates to {@code PulsarEventPublisher}.
 */
public interface OutboxPublisher {

    /**
     * Forward a raw (already-serialized) outbox row to the messaging backbone.
     *
     * @param eventType the event type (for routing/logging)
     * @param json      the JSON-serialized event payload
     * @throws Exception if the send fails after all retries — the relay catches
     *                   this, increments {@code retry_count}, and keeps the row
     *                   unpublished for the next tick
     */
    void publishRaw(String eventType, String json) throws Exception;
}
