package com.ep.web.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * D-234: generic transactional-outbox relay — the second half of the outbox
 * pattern. Drains unpublished rows from {@code ep_outbox} and forwards them to
 * the broker via the host-provided {@link OutboxPublisherPort}.
 *
 * <p><b>Own scheduler, zero host intrusion</b>: unlike Spring's
 * {@code @Scheduled}, this relay runs on a private daemon
 * {@link ScheduledExecutorService} (same pattern as {@code CacheStatsLogger}),
 * so hosts need no {@code @EnableScheduling} and adding the outbox never
 * accidentally activates other latent {@code @Scheduled} beans.</p>
 *
 * <p><b>Failure handling</b> (identical to the order-platform reference
 * implementation, D-217): a publish failure increments {@code retry_count} and
 * leaves the row unpublished for the next tick; after {@code max-retries} the
 * row is marked published (stops the infinite loop) with an ERROR log for
 * operator follow-up.</p>
 *
 * <p><b>Multi-instance note</b> (documented trade-off, mirrors D-217): relay
 * instances do not row-lock; on multi-replica deployments the same row can be
 * published more than once. Consumers dedup on the deterministic
 * {@code event_id} — which is the outbox pattern's standard pairing.</p>
 */
public class OutboxRelay implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Row shape: eventId, eventType, payload, retryCount. */
    private record Row(String eventId, String eventType, String payload, int retryCount) {
    }

    private final JdbcTemplate jdbc;
    private final OutboxPublisherPort publisher;
    private final int batchSize;
    private final int maxRetries;
    private final ScheduledExecutorService scheduler;

    public OutboxRelay(JdbcTemplate jdbc, OutboxPublisherPort publisher,
                       OutboxProperties properties) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.batchSize = Math.max(1, properties.getBatchSize());
        this.maxRetries = Math.max(1, properties.getMaxRetries());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, properties.getRelayThreadName());
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleWithFixedDelay(this::drainSafely,
                properties.getRelayIntervalMillis(), properties.getRelayIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    private void drainSafely() {
        try {
            drain();
        } catch (Exception e) {
            log.warn("D-234: outbox relay tick failed: {}", e.getMessage());
        }
    }

    /** One drain tick: read a batch, publish each, stamp the outcome. Package-private for tests. */
    void drain() {
        List<Row> rows = jdbc.query(
                "SELECT event_id, event_type, payload, retry_count FROM ep_outbox "
                        + "WHERE published_at IS NULL ORDER BY created_at ASC LIMIT " + batchSize,
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
        if (rows.isEmpty()) {
            return;
        }
        log.info("D-234: outbox relay processing {} events", rows.size());
        for (Row row : rows) {
            try {
                publisher.publishRaw(row.eventType(), row.payload());
                jdbc.update("UPDATE ep_outbox SET published_at = ? WHERE event_id = ?",
                        Timestamp.from(Instant.now()), row.eventId());
            } catch (Exception e) {
                int nextRetry = row.retryCount() + 1;
                if (nextRetry >= maxRetries) {
                    log.error("D-234: outbox event {} exceeded max retries ({}), giving up: {}",
                            row.eventId(), maxRetries, e.getMessage());
                    jdbc.update("UPDATE ep_outbox SET published_at = ?, retry_count = ? WHERE event_id = ?",
                            Timestamp.from(Instant.now()), nextRetry, row.eventId());
                } else {
                    jdbc.update("UPDATE ep_outbox SET retry_count = ? WHERE event_id = ?",
                            nextRetry, row.eventId());
                    log.warn("D-234: outbox event {} publish failed (retry {}/{}): {}",
                            row.eventId(), nextRetry, maxRetries, e.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
