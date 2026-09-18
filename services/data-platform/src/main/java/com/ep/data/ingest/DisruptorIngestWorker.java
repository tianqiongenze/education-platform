package com.ep.data.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * D-216 (audit P0-3): the REAL worker behind the ingest RingBuffer.
 *
 * <p>Previously {@code DisruptorIngestService}'s handler was a debug-logging
 * no-op and the javadoc claimed a worker that did not exist — every event
 * entering the RingBuffer was silently consumed and lost (Iron Law 9
 * violation: artifact existed, nothing ran).</p>
 *
 * <p>This worker implements the batch stage of SDD §5.5: accumulate events
 * (up to {@code batchSize} or {@code flushIntervalMs}, whichever first), then
 * JDBC batch-write them to {@code ep_ingest_events}. Batches that fail after
 * {@code maxRetries} attempts are handed to {@link PulsarFallback} so no
 * accepted event is ever dropped.</p>
 */
@Component
public class DisruptorIngestWorker {

    private static final Logger log = LoggerFactory.getLogger(DisruptorIngestWorker.class);

    static final String DDL = """
            CREATE TABLE IF NOT EXISTS ep_ingest_events (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              source_cell VARCHAR(64),
              table_name VARCHAR(128),
              op_type VARCHAR(16),
              ts_ms BIGINT,
              payload JSON,
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )""";

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final PulsarFallback pulsarFallback;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int batchSize;
    private final long flushIntervalMs;
    private final int maxRetries;

    private final List<IngestEvent> buffer = new ArrayList<>();
    private final Object bufferLock = new Object();
    private Thread flusher;
    private volatile boolean running;
    private long persistedTotal;
    private long fallbackTotal;

    public DisruptorIngestWorker(ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            PulsarFallback pulsarFallback,
            @Value("${ep.ingest.batch-size:500}") int batchSize,
            @Value("${ep.ingest.flush-interval-ms:500}") long flushIntervalMs,
            @Value("${ep.ingest.max-retries:3}") int maxRetries) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.pulsarFallback = pulsarFallback;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.maxRetries = maxRetries;
    }

    /** Convenience constructor for tests — uses production defaults. */
    DisruptorIngestWorker(ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            PulsarFallback pulsarFallback) {
        this(jdbcTemplateProvider, pulsarFallback, 500, 500, 3);
    }

    @PostConstruct
    public void start() {
        running = true;
        flusher = Thread.ofVirtual().name("ingest-batch-flusher").start(this::flushLoop);
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc != null) {
            try {
                jdbc.execute(DDL);
                log.info("[IngestWorker] table ep_ingest_events ready (batch={}, intervalMs={}, retries={})",
                        batchSize, flushIntervalMs, maxRetries);
            } catch (Exception e) {
                log.warn("[IngestWorker] DDL failed (worker will keep buffering; batch writes will retry): {}",
                        e.getMessage());
            }
        } else {
            log.warn("[IngestWorker] no DataSource — events accumulate in memory and flush to fallback spool");
        }
    }

    /**
     * Called by the RingBuffer consumer for every accepted event. Appends to
     * the batch buffer; flushing happens on size or timer trigger.
     */
    public void onEvent(IngestEvent event) {
        if (event == null || event.getEnvelope() == null) {
            return;
        }
        boolean shouldFlush = false;
        synchronized (bufferLock) {
            buffer.add(event);
            if (buffer.size() >= batchSize) {
                shouldFlush = true;
            }
        }
        if (shouldFlush) {
            flush();
        }
    }

    private void flushLoop() {
        while (running) {
            try {
                Thread.sleep(flushIntervalMs);
                flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Drain the buffer and persist. Visible for tests. */
    public void flush() {
        List<IngestEvent> batch;
        synchronized (bufferLock) {
            if (buffer.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(buffer);
            buffer.clear();
        }
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            // No persistence layer configured — degrade the whole batch so the
            // events remain recoverable instead of vanishing.
            batch.forEach(pulsarFallback::publish);
            fallbackTotal += batch.size();
            return;
        }
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                jdbc.batchUpdate(
                        "INSERT INTO ep_ingest_events(source_cell, table_name, op_type, ts_ms, payload) VALUES (?,?,?,?,?)",
                        batch.stream().map(e -> new Object[] {
                                e.getSourceCell(),
                                e.getEnvelope().getTable(),
                                e.getEnvelope().getOpType() == null ? null : e.getEnvelope().getOpType().name(),
                                e.getEnvelope().getTsMs(),
                                payloadJson(e)
                        }).toList());
                persistedTotal += batch.size();
                return;
            } catch (Exception ex) {
                log.warn("[IngestWorker] batch write attempt {}/{} failed ({} events): {}",
                        attempt, maxRetries, batch.size(), ex.getMessage());
                if (attempt == maxRetries) {
                    log.error("[IngestWorker] batch persist exhausted retries — routing {} events to fallback",
                            batch.size());
                    batch.forEach(pulsarFallback::publish);
                    fallbackTotal += batch.size();
                }
            }
        }
    }

    private String payloadJson(IngestEvent e) {
        try {
            var node = mapper.createObjectNode();
            node.put("before", e.getEnvelope().getBefore());
            node.put("after", e.getEnvelope().getAfter());
            return mapper.writeValueAsString(node);
        } catch (Exception ex) {
            return "{\"error\":\"serialize_failed\"}";
        }
    }

    /** Worker metrics for /stats exposure. */
    public long persistedTotal() {
        return persistedTotal;
    }

    public long fallbackTotal() {
        return fallbackTotal;
    }

    public int buffered() {
        synchronized (bufferLock) {
            return buffer.size();
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (flusher != null) {
            flusher.interrupt();
        }
        flush(); // final drain
        log.info("[IngestWorker] stopped — persisted={}, fallback={}", persistedTotal, fallbackTotal);
    }
}
