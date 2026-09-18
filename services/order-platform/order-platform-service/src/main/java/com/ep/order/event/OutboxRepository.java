package com.ep.order.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * D-217: JPA repository for the transactional-outbox table {@code ep_outbox}.
 * <p>
 * The relay ({@link OutboxRelayService}) drains unsent rows in
 * {@code created_at} order via
 * {@link #findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()}.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntity, String> {

    /**
     * Fetch the next batch of at most 50 unpublished outbox rows, oldest first.
     * The relay calls this each tick and publishes each row to Pulsar.
     */
    List<OutboxEntity> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
}
