package com.ep.web.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * D-234: transactional-outbox write facade.
 *
 * <p>Services call {@link #write(String, String, String, String, String)}
 * instead of (or in addition to) a fire-and-forget broker publish. The row
 * lands in the {@code ep_outbox} table; the background {@link OutboxRelay}
 * drains it to the broker with bounded retries.</p>
 *
 * <p><b>Transaction semantics</b>: when the caller is inside a transaction the
 * row joins it (atomic with the business write — the textbook outbox pattern).
 * When called post-commit (the "bridge" mode used by the migrated
 * best-effort publishers), the write runs in {@code REQUIRES_NEW} so it still
 * lands durably even with no ambient transaction. A failed bridge write
 * propagates the exception to the caller — an explicit, logged failure beats a
 * silently lost event.</p>
 *
 * <p>Implemented on {@link JdbcTemplate} rather than JPA so hosts need no
 * {@code @EntityScan} changes and this library needs no JPA dependency.</p>
 */
public class OutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNewTx;
    private final Supplier<String> idGenerator;

    public OutboxWriter(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this(jdbc, txManager, () -> UUID.randomUUID().toString().replace("-", ""));
    }

    /** Test-friendly: injectable id generator for deterministic event ids. */
    OutboxWriter(JdbcTemplate jdbc, PlatformTransactionManager txManager, Supplier<String> idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
        if (txManager != null) {
            TransactionTemplate t = new TransactionTemplate(txManager);
            t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTx = t;
        } else {
            this.requiresNewTx = null;
        }
    }

    /**
     * Persist an event row. When an ambient transaction exists the row joins
     * it; otherwise the write runs in a new independent transaction.
     *
     * @param aggregateType e.g. {@code "Course"} — routing/audit
     * @param aggregateId   the aggregate's business id — routing/audit
     * @param eventType     concrete event type name — Pulsar routing key
     * @param payloadJson   JSON-serialized event record
     * @return the deterministic event id (consumers dedup on it)
     */
    public String write(String aggregateType, String aggregateId, String eventType, String payloadJson) {
        String eventId = eventType + ":" + aggregateId + ":" + idGenerator.get();
        org.springframework.transaction.support.TransactionCallback<String> insert = status -> {
            jdbc.update(
                    "INSERT INTO ep_outbox (event_id, aggregate_type, aggregate_id, event_type, payload, created_at, retry_count) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 0)",
                    eventId, aggregateType, aggregateId, eventType, payloadJson, java.sql.Timestamp.from(Instant.now()));
            return eventId;
        };
        if (requiresNewTx != null) {
            requiresNewTx.execute(insert);
        } else {
            insert.doInTransaction(null);
        }
        log.debug("D-234: outbox row written eventId={} type={} aggregate={}/{}", eventId, eventType, aggregateType, aggregateId);
        return eventId;
    }

    /** Total rows pending publication (diagnostics endpoint helper). */
    public long pendingCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM ep_outbox WHERE published_at IS NULL", Long.class);
        return n == null ? 0 : n;
    }
}
