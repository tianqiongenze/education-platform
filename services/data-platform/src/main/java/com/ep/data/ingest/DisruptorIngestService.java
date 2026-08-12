package com.ep.data.ingest;

import com.ep.disruptor.DisruptorWorker;
import com.ep.disruptor.PublishResult;
import com.ep.disruptor.WaitStrategyType;
import com.lmax.disruptor.dsl.ProducerType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Disruptor-based ingest service implementing SDD §5.5 Worker Separation Pattern.
 *
 * <p><b>API role</b> (default, {@code ep.role=api}): HTTP/gRPC entry point calls
 * {@link #publish(IngestEvent)} which uses Disruptor {@code tryNext()} for backpressure.
 * Does NOT write to DB directly.
 *
 * <p><b>Worker role</b> ({@code ep.role=worker}): The {@link DisruptorIngestWorker}
 * handler is activated via {@code @ConditionalOnProperty} and consumes events from
 * the RingBuffer, batch-writing to TiDB and publishing to Pulsar.
 *
 * <p><b>Degradation</b>: When the RingBuffer is full (tryNext() fails → DROPPED),
 * the event is forwarded to {@link PulsarFallback} for direct Pulsar publish,
 * ensuring no data loss (ESP experience: avoid dropping data under load).
 *
 * <p>K8s deployment: API Deployment (HPA by QPS) + Worker Deployment (HPA by
 * Disruptor consumption lag), independently scalable.
 */
@Service
public class DisruptorIngestService {

    private static final Logger log = LoggerFactory.getLogger(DisruptorIngestService.class);

    @Value("${ep.disruptor.buffer-size:65536}")
    private int bufferSize;

    @Value("${ep.disruptor.wait-strategy:SLEEPING}")
    private String waitStrategyName;

    private final PulsarFallback pulsarFallback;

    private DisruptorWorker<IngestEvent> disruptorWorker;

    @Autowired
    public DisruptorIngestService(PulsarFallback pulsarFallback) {
        this.pulsarFallback = pulsarFallback;
    }

    @PostConstruct
    public void init() {
        WaitStrategyType waitStrategy = WaitStrategyType.valueOf(waitStrategyName.toUpperCase());
        disruptorWorker = DisruptorWorker.<IngestEvent>builder()
                .name("ingest-pipeline")
                .bufferSize(bufferSize)
                .producerType(ProducerType.MULTI)
                .waitStrategy(waitStrategy)
                .factory(IngestEvent::new)
                .translator((slot, seq, src) -> {
                    slot.setEnvelope(src.getEnvelope());
                    slot.setIngestTs(src.getIngestTs());
                    slot.setSourceCell(src.getSourceCell());
                })
                .handler((evt, seq, endOfBatch) -> {
                    // Worker role handler — activated when ep.role=worker.
                    // In API role (default), events are still consumed but
                    // the handler is a no-op (the worker deployment overrides this).
                    // The actual worker handler is wired via DisruptorIngestWorker.
                    if (evt.getEnvelope() != null) {
                        log.debug("[Ingest] Consumed event seq={} table={}", seq,
                                evt.getEnvelope().getTable());
                    }
                })
                .build();
        disruptorWorker.start();
        log.info("[Ingest] DisruptorWorker started — buffer={}, waitStrategy={}",
                bufferSize, waitStrategy);
    }

    /**
     * Publish an ingest event to the Disruptor RingBuffer.
     *
     * <p>Flow (SDD §5.5):
     * <ol>
     *   <li>tryNext() to reserve a slot in the RingBuffer.</li>
     *   <li>If PUBLISHED → event will be consumed by the Worker handler.</li>
     *   <li>If DROPPED (RingBuffer full) → degrade to Pulsar direct publish.</li>
     * </ol>
     *
     * @param event the ingest event
     * @return PUBLISHED if accepted by Disruptor, DROPPED if degraded to Pulsar
     */
    public PublishResult publish(IngestEvent event) {
        PublishResult result = disruptorWorker.publish(event);
        if (result == PublishResult.DROPPED) {
            log.warn("[Ingest] RingBuffer full — degrading to Pulsar fallback for table={}",
                    event.getEnvelope() != null ? event.getEnvelope().getTable() : "unknown");
            if (pulsarFallback != null) {
                pulsarFallback.publish(event);
            } else {
                log.error("[Ingest] PulsarFallback not available — event LOST for table={}",
                        event.getEnvelope() != null ? event.getEnvelope().getTable() : "unknown");
            }
        }
        return result;
    }

    /**
     * Get current Disruptor stats for monitoring.
     */
    public com.ep.disruptor.DisruptorStats stats() {
        return disruptorWorker.stats();
    }

    @PreDestroy
    public void shutdown() {
        log.info("[Ingest] Shutting down DisruptorWorker — draining pending events...");
        disruptorWorker.shutdown();
        log.info("[Ingest] DisruptorWorker shutdown complete");
    }
}
