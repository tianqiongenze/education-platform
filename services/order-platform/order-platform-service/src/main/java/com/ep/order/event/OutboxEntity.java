package com.ep.order.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * D-217: Transactional-outbox row.
 * <p>
 * Persisted in the <b>same</b> JPA transaction as the aggregate state change
 * (e.g. {@code OrderModel} PENDING -> PAID), so the event write is atomic with
 * the business write — there is no dual-write window where the DB commits but
 * the Pulsar publish is lost (or vice-versa). A separate relay
 * ({@link OutboxRelayService}) drains unsent rows and forwards them to Pulsar.
 * <p>
 * Columns:
 * <ul>
 *   <li>{@code event_id} — PK; the deterministic {@link DomainEvent#eventId()}
 *       ({@code eventType:aggregateId:occurredAtMillis}) so consumers dedup
 *       redeliveries idempotently.</li>
 *   <li>{@code aggregate_type} — e.g. {@code "Order"}; routing/audit.</li>
 *   <li>{@code aggregate_id} — e.g. the order id; routing/audit.</li>
 *   <li>{@code event_type} — concrete event class name (e.g.
 *       {@code OrderPaidEvent}); Pulsar topic key.</li>
 *   <li>{@code payload} — the JSON-serialized event record.</li>
 *   <li>{@code created_at} — row creation time (relay order-by).</li>
 *   <li>{@code published_at} — non-null once the relay has confirmed the Pulsar
 *       send; the relay filters on {@code published_at IS NULL}.</li>
 *   <li>{@code retry_count} — bounded publish retries before the relay gives up
 *       and marks the row published to avoid an infinite retry loop.</li>
 * </ul>
 */
@Entity
@Table(name = "ep_outbox")
public class OutboxEntity {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OutboxEntity that)) return false;
        return Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }
}
