package com.ep.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ep.web.common.event.EventEnvelope;

import java.time.Instant;

/**
 * Domain event marker (lightweight, no framework dependency).
 * <p>
 * Aggregate roots (e.g. {@code OrderModel}) record {@link DomainEvent}
 * implementations into an internal collection that the application service
 * pulls (via {@code pullEvents()}) after a successful transaction and publishes
 * to the event bus (Pulsar).
 * <p>
 * D-event-shared: ep-web-common now defines the platform-wide contract
 * {@code com.ep.web.common.event.DomainEvent}. This local interface cannot be
 * removed yet: the order event records
 * ({@code OrderPaidEvent(long aggregateId, …, Instant occurredAt)}) have
 * record-component accessors whose signatures clash with the shared ones
 * ({@code String aggregateId()}, {@code long occurredAt()}), and
 * {@code OrderModel}/{@code OrderServiceImpl}/{@code OrderCompensableAction}
 * reference this FQCN directly. Events lift into the shared, versioned
 * contract via {@link #envelope()}.
 */
public interface DomainEvent {

    long getAggregateId();

    Instant getOccurredAt();

    /**
     * D-217: Deterministic event id ({@code eventType:aggregateId:occurredAtMillis})
     * lifted from the shared-contract {@link EventEnvelope}. Used as the outbox
     * primary key so consumers can dedup redeliveries idempotently.
     */
    default String eventId() {
        return envelope().eventId();
    }

    /**
     * D-217: Event type for routing/topic selection. Defaults to the simple
     * class name of the concrete event record (e.g. {@code OrderPaidEvent}).
     */
    default String eventType() {
        return getClass().getSimpleName();
    }

    /**
     * D-217: JSON serialization of the event record, used to persist the event
     * payload into the outbox table. The shared mapper registers the JSR-310
     * module and disables timestamp-as-numbers so {@link Instant} fields stay
     * ISO-8601 strings.
     */
    default String toJson() {
        return ToOutboxJson.toJson(this);
    }

    /** D-217: thread-safe outbox JSON mapper (JSR-310 aware, ISO-8601 timestamps). */
    final class ToOutboxJson {
        static final ObjectMapper MAPPER = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        static String toJson(DomainEvent event) {
            try {
                return MAPPER.writeValueAsString(event);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "D-217: failed to serialize outbox event " + event.eventId(), e);
            }
        }
    }

    /**
     * Shared-contract view of this event (eventId/eventType/occurredAt-millis/
     * String-aggregateId/schemaVersion). The deterministic eventId
     * ({@code eventType:aggregateId:occurredAtMillis}) lets consumers dedup
     * redeliveries idempotently.
     */
    default EventEnvelope envelope() {
        return EventEnvelope.of(getClass().getSimpleName(), getAggregateId(), getOccurredAt());
    }
}
