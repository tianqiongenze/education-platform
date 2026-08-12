package com.ep.data.ingest;

import com.ep.data.cdc.CdcEnvelope;
import com.ep.disruptor.PublishResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for CDC event ingestion via Disruptor pipeline (SDD §5.5).
 *
 * <p>API role entry point — receives CDC events and publishes to the Disruptor
 * RingBuffer for async processing by the Worker role.
 *
 * <p>Responses:
 * <ul>
 *   <li>202 Accepted — event published to Disruptor successfully</li>
 *   <li>429 Too Many Requests — RingBuffer full, event degraded to Pulsar fallback</li>
 *   <li>400 Bad Request — invalid payload</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/ingest")
@Tag(name = "数据摄入", description = "CDC事件Disruptor摄入管道")
public class DisruptorIngestController {

    private static final Logger log = LoggerFactory.getLogger(DisruptorIngestController.class);

    private final DisruptorIngestService ingestService;

    public DisruptorIngestController(DisruptorIngestService ingestService) {
        this.ingestService = ingestService;
    }

    /**
     * Ingest a CDC event via the Disruptor pipeline.
     *
     * @param envelope the CDC envelope carrying the row change event
     * @return 202 if accepted by Disruptor, 429 if degraded to Pulsar
     */
    @PostMapping("/cdc")
    @Operation(summary = "摄入CDC事件", description = "通过Disruptor RingBuffer异步处理CDC事件")
    public ResponseEntity<String> ingestCdc(@RequestBody CdcEnvelope envelope) {
        if (envelope == null || envelope.getTable() == null) {
            return ResponseEntity.badRequest().body("invalid envelope: table is required");
        }

        IngestEvent event = new IngestEvent();
        event.setEnvelope(envelope);
        event.setIngestTs(System.currentTimeMillis());
        event.setSourceCell(envelope.getSourceCell() != null ? envelope.getSourceCell() : "default");

        PublishResult result = ingestService.publish(event);
        if (result == PublishResult.PUBLISHED) {
            log.debug("[IngestController] Event accepted by Disruptor — table={}", envelope.getTable());
            return ResponseEntity.accepted().body("{\"status\":\"accepted\",\"table\":\"" + envelope.getTable() + "\"}");
        } else {
            log.warn("[IngestController] Event degraded to Pulsar fallback — table={}", envelope.getTable());
            return ResponseEntity.status(429)
                    .body("{\"status\":\"degraded\",\"table\":\"" + envelope.getTable()
                            + "\",\"hint\":\"RingBuffer full — event sent to Pulsar fallback\"}");
        }
    }

    /**
     * Get Disruptor pipeline stats for monitoring.
     */
    @PostMapping("/stats")
    @Operation(summary = "Disruptor管道状态", description = "返回RingBuffer的published/dropped/consumed计数")
    public ResponseEntity<com.ep.disruptor.DisruptorStats> stats() {
        return ResponseEntity.ok(ingestService.stats());
    }
}
