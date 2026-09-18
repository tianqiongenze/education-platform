package com.ep.grading.ide.yjs;

import com.ep.disruptor.DisruptorStats;
import com.ep.disruptor.DisruptorWorker;
import com.ep.disruptor.PublishResult;
import com.ep.disruptor.WaitStrategyType;
import com.lmax.disruptor.dsl.ProducerType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * D-216: single Disruptor worker (SDD §5.5 Worker Separation Pattern) that
 * buffers high-frequency Yjs collaborative-edit updates and batches them into
 * the {@link YjsSnapshotStore}.
 *
 * <h3>Why a single worker here (not the multi-stage pipeline)?</h3>
 * The Yjs update path is a single hot consumer: N WebSocket clients push
 * binary UPDATE frames, each one must be appended to the in-memory
 * {@link YjsDocument} and periodically snapshotted to Redis. There is no
 * ordered multi-stage transformation — only a buffering/batch-decoupling need.
 * A single {@link DisruptorWorker} (the same pattern as portal-bff's
 * {@code GatewayRingBuffer}) is the right shape: a pre-allocated RingBuffer
 * absorbs the burst, a single consumer thread drains it, and a
 * drop-on-full policy yields 429-grade backpressure to the ws-gateway instead
 * of OOM-ing under load.
 *
 * <h3>Backpressure</h3>
 * When the RingBuffer is full, {@link #submit} returns {@link PublishResult#DROPPED}.
 * The caller (the ws-gateway room) should treat DROPPED as "update not
 * snapshotted this round" — the CRDT state is held in-memory by the
 * {@link YjsDocument} regardless, and the next accepted update will re-trigger
 * a snapshot, so no data is lost, only a delayed snapshot.
 *
 * <p>Conditionally activated when {@code ep.disruptor.enabled=true} (the same
 * flag as the grading-events pipeline). When OFF, the ws-gateway falls back to
 * calling {@link YjsSnapshotStore#saveSnapshot} directly (synchronous).
 */
@Component
@ConditionalOnProperty(name = "ep.disruptor.enabled", havingValue = "true")
public class YjsUpdateBuffer {

    private static final Logger log = LoggerFactory.getLogger(YjsUpdateBuffer.class);

    @Value("${ep.disruptor.yjs.buffer-size:16384}")
    private int bufferSize;

    @Value("${ep.disruptor.yjs.wait-strategy:SLEEPING}")
    private String waitStrategyName;

    private final YjsSnapshotStore snapshotStore;
    private final YjsDocumentRegistry documentRegistry;

    private DisruptorWorker<YjsUpdateEvent> worker;

    public YjsUpdateBuffer(YjsSnapshotStore snapshotStore,
                           YjsDocumentRegistry documentRegistry) {
        this.snapshotStore = snapshotStore;
        this.documentRegistry = documentRegistry;
    }

    @PostConstruct
    public void init() {
        WaitStrategyType waitStrategy = WaitStrategyType.valueOf(waitStrategyName.toUpperCase());
        worker = DisruptorWorker.<YjsUpdateEvent>builder()
                .name("grading-yjs-updates")
                .bufferSize(bufferSize)
                .producerType(ProducerType.MULTI)
                .waitStrategy(waitStrategy)
                .factory(YjsUpdateEvent::new)
                .translator((slot, seq, src) -> {
                    slot.docId = src.docId;
                    slot.update = src.update;
                })
                .handler((evt, seq, endOfBatch) -> {
                    if (evt.docId == null || evt.update == null) {
                        return;
                    }
                    YjsDocument document = documentRegistry.getOrCreate(evt.docId);
                    document.applyUpdate(evt.update);
                    snapshotStore.saveSnapshot(evt.docId, document);
                })
                .build();
        worker.start();
        log.info("[YjsUpdateBuffer] Started — buffer={}, waitStrategy={}",
                bufferSize, waitStrategy);
    }

    /**
     * Submit a Yjs document update for batched processing.
     *
     * @param docId  the Yjs document id
     * @param update the raw binary update frame (opaque CRDT update)
     * @return PUBLISHED if accepted by the RingBuffer, DROPPED if full
     *         (caller should fall back to synchronous apply+snapshot)
     */
    public PublishResult submit(String docId, byte[] update) {
        if (docId == null || update == null || update.length == 0) {
            return PublishResult.DROPPED;
        }
        YjsUpdateEvent event = new YjsUpdateEvent();
        event.docId = docId;
        event.update = update;
        return worker.publish(event);
    }

    /** Current RingBuffer stats for monitoring. */
    public DisruptorStats stats() {
        return worker.stats();
    }

    @PreDestroy
    public void shutdown() {
        log.info("[YjsUpdateBuffer] Shutting down — draining pending updates...");
        worker.shutdown();
        log.info("[YjsUpdateBuffer] Shutdown complete");
    }

    /** Disruptor slot for one Yjs update. */
    public static final class YjsUpdateEvent {
        public String docId;
        public byte[] update;
    }
}
