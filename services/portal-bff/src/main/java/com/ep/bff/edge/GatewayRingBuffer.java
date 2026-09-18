package com.ep.bff.edge;

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
 * Disruptor RingBuffer(65536) ingress for the BFF gateway (SDD §5.2.1).
 *
 * <p>Receives raw HTTP requests from {@link NettyEdgeServer} and buffers them
 * in a LMAX Disruptor RingBuffer for batch dispatch to the Jetty WebMVC backend.
 *
 * <h3>Backpressure (drop-on-full)</h3>
 * When the RingBuffer is full, {@link #publish(GatewayEvent)} returns
 * {@link PublishResult#DROPPED} and the caller ({@link NettyEdgeServer})
 * immediately responds with <b>429 Too Many Requests</b>.
 *
 * <h3>Batch Dispatch</h3>
 * The consumer ({@link GatewayBatchDispatcher}) processes events in batches
 * of up to 64 (or 10ms timeout), forwarding each to {@code http://localhost:8090}
 * (the Jetty WebMVC server). This amortizes the per-request overhead.
 *
 * <p>Conditionally activated when {@code ep.gateway.netty.enabled=true}.
 *
 * @see NettyEdgeServer
 * @see GatewayBatchDispatcher
 */
@Component
@ConditionalOnProperty(name = "ep.gateway.netty.enabled", havingValue = "true")
public class GatewayRingBuffer {

    private static final Logger log = LoggerFactory.getLogger(GatewayRingBuffer.class);

    /** SDD §5.2.1: RingBuffer size 65536. */
    public static final int DEFAULT_BUFFER_SIZE = 65536;

    /** SDD §5.2.1: batch dispatch size 64. */
    public static final int DEFAULT_BATCH_SIZE = 64;

    @Value("${ep.gateway.ring-buffer.size:65536}")
    private int bufferSize;

    @Value("${ep.gateway.ring-buffer.batch-size:64}")
    private int batchSize;

    @Value("${ep.gateway.ring-buffer.batch-timeout-ms:10}")
    private long batchTimeoutMs;

    @Value("${ep.gateway.backend-url:http://localhost:8090}")
    private String backendUrl;

    private DisruptorWorker<GatewayEvent> disruptorWorker;
    private GatewayBatchDispatcher dispatcher;

    @PostConstruct
    public void init() {
        // Create the batch dispatcher that forwards to Jetty backend
        dispatcher = new GatewayBatchDispatcher(backendUrl, batchSize, batchTimeoutMs);

        // Create and start the Disruptor worker
        disruptorWorker = DisruptorWorker.<GatewayEvent>builder()
                .name("gateway-ingress")
                .bufferSize(bufferSize)
                .producerType(ProducerType.MULTI)
                .waitStrategy(WaitStrategyType.SLEEPING)
                .factory(GatewayEvent::new)
                .translator((slot, seq, src) -> {
                    slot.setMethod(src.getMethod());
                    slot.setPath(src.getPath());
                    slot.setHeaders(src.getHeaders());
                    slot.setBody(src.getBody());
                    slot.setRemoteAddr(src.getRemoteAddr());
                    slot.setTimestamp(src.getTimestamp());
                    slot.setChannel(src.getChannel());
                })
                .handler(dispatcher)
                .build();
        disruptorWorker.start();
        log.info("[GatewayRingBuffer] Started — buffer={}, batchSize={}, batchTimeout={}ms, backend={}",
                bufferSize, batchSize, batchTimeoutMs, backendUrl);
    }

    /**
     * Publish a gateway event to the RingBuffer.
     *
     * @param event the HTTP request event
     * @return PUBLISHED if accepted, DROPPED if RingBuffer full (caller should return 429)
     */
    public PublishResult publish(GatewayEvent event) {
        return disruptorWorker.publish(event);
    }

    /**
     * Get current RingBuffer stats for monitoring.
     */
    public com.ep.disruptor.DisruptorStats stats() {
        return disruptorWorker.stats();
    }

    @PreDestroy
    public void shutdown() {
        log.info("[GatewayRingBuffer] Shutting down — draining pending events...");
        disruptorWorker.shutdown();
        dispatcher.shutdown();
        log.info("[GatewayRingBuffer] Shutdown complete");
    }
}
