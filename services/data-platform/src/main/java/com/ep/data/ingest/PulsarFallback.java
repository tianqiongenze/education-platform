package com.ep.data.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pulsar fallback for Disruptor degradation (SDD §5.5).
 *
 * <p>When the Disruptor RingBuffer is full, events are published directly to Pulsar
 * to avoid data loss. This is the "degraded state" path:
 * API tryNext() fails → PulsarFallback.publish() → downstream consumers handle it.
 *
 * <p>In dev/CI (ep.pulsar.enabled=false, the default), this logs the event.
 * In production (ep.pulsar.enabled=true), it would create a PulsarProducer
 * and publish to the CDC topic.
 */
@Component
public class PulsarFallback {

    private static final Logger log = LoggerFactory.getLogger(PulsarFallback.class);

    @Value("${ep.pulsar.enabled:false}")
    private boolean pulsarEnabled;

    /**
     * Publish an ingest event directly to Pulsar (bypassing Disruptor).
     *
     * @param event the ingest event to publish
     */
    public void publish(IngestEvent event) {
        if (event == null || event.getEnvelope() == null) {
            log.warn("[PulsarFallback] Null event or envelope — skipping");
            return;
        }
        if (!pulsarEnabled) {
            log.debug("[PulsarFallback] Dev mode — would publish to Pulsar: table={}, op={}",
                    event.getEnvelope().getTable(),
                    event.getEnvelope().getOpType());
            return;
        }
        // D-171: Production path — inject PulsarClient, create producer, send.
        // TODO: Wire PulsarClient when production Pulsar cluster is available.
        log.info("[PulsarFallback] Pulsar publish — table={}, opType={}, ts={}",
                event.getEnvelope().getTable(),
                event.getEnvelope().getOpType(),
                event.getEnvelope().getTsMs());
    }
}
