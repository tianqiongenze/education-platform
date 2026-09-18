package com.ep.order.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * D-217: Transactional-outbox relay.
 * <p>
 * A scheduled task that drains unsent rows from {@code ep_outbox} and forwards
 * each to Pulsar via a {@link OutboxPublisher} strategy port. Once a row is
 * confirmed published its {@code published_at} is stamped so the next tick
 * skips it.
 * <p>
 * The relay is the <b>second half</b> of the transactional-outbox pattern: the
 * <b>first half</b> is {@code OrderServiceImpl#publishEvents} which writes the
 * outbox row in the same JPA transaction as the aggregate state change. Together
 * they close the dual-write gap (no event lost on commit, no phantom event on
 * rollback).
 * <p>
 * <b>Architecture:</b> the relay lives in the {@code event} package and depends
 * only on the {@link OutboxPublisher} port (also in {@code event}), not on the
 * {@code infrastructure} package's {@code PulsarEventPublisher}. This keeps the
 * slice dependency graph acyclic ({@code infrastructure → event} only, never
 * back), satisfying the ArchUnit cycle rule.
 * <p>
 * Failure handling: a publish failure increments {@code retry_count} and leaves
 * {@code published_at} null so the next tick retries. After {@link #MAX_RETRIES}
 * attempts the row is marked published (to avoid an infinite retry loop) and
 * the failure is logged at ERROR for operator follow-up.
 * <p>
 * EP_DEV_ONLY: when Pulsar is disabled ({@code ep.pulsar.enabled=false}, the
 * dev/test default) the publisher port is a no-op that returns successfully, so
 * the relay still marks rows published — tests do not need a running broker.
 */
@Component
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    /** Bounded retries before a row is given up on (marked published to stop the loop). */
    static final int MAX_RETRIES = 5;

    private final OutboxRepository outboxRepository;
    private final OutboxPublisher publisher;

    public OutboxRelayService(OutboxRepository outboxRepository, OutboxPublisher publisher) {
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
    }

    /**
     * Drain up to 50 unpublished outbox rows every 5 seconds and forward them
     * to Pulsar. {@code @Transactional} so the {@code published_at}/retry
     * updates commit atomically per batch.
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void relay() {
        List<OutboxEntity> unpublished = outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (unpublished.isEmpty()) {
            return;
        }
        log.info("D-217: outbox relay processing {} events", unpublished.size());
        for (OutboxEntity entity : unpublished) {
            try {
                publisher.publishRaw(entity.getEventType(), entity.getPayload());
                entity.setPublishedAt(Instant.now());
                outboxRepository.save(entity);
            } catch (Exception e) {
                entity.setRetryCount(entity.getRetryCount() + 1);
                if (entity.getRetryCount() >= MAX_RETRIES) {
                    log.error("D-217: outbox event {} exceeded max retries, giving up: {}",
                            entity.getEventId(), e.getMessage());
                    // mark as processed to avoid an infinite retry loop
                    entity.setPublishedAt(Instant.now());
                }
                outboxRepository.save(entity);
                log.warn("D-217: outbox event {} publish failed (retry {}): {}",
                        entity.getEventId(), entity.getRetryCount(), e.getMessage());
            }
        }
    }
}
