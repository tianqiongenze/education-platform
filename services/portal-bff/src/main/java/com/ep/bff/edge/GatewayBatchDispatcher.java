package com.ep.bff.edge;

import com.lmax.disruptor.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch dispatcher for gateway ingress events (SDD §5.2.1).
 *
 * <p>Implements {@link EventHandler} to consume {@link GatewayEvent}s from the
 * Disruptor RingBuffer. Events are accumulated into batches (up to
 * {@code batchSize} or {@code batchTimeoutMs} elapsed) and then forwarded
 * one-by-one to the Jetty WebMVC backend at {@code backendUrl}.
 *
 * <p>Although events are dispatched individually (HTTP semantics require one
 * request per response), the batch processing amortizes Disruptor consumer
 * overhead — the consumer thread stays hot between dispatches without
 * context-switching per event.
 *
 * <p>Uses JDK {@link HttpClient} (virtual-thread compatible, no synchronized blocks).
 */
public class GatewayBatchDispatcher implements EventHandler<GatewayEvent> {

    private static final Logger log = LoggerFactory.getLogger(GatewayBatchDispatcher.class);

    private final String backendUrl;
    private final int batchSize;
    private final long batchTimeoutMs;
    private final HttpClient httpClient;
    private final List<GatewayEvent> pendingBatch = new ArrayList<>();
    private long lastFlushTime = System.currentTimeMillis();

    public GatewayBatchDispatcher(String backendUrl, int batchSize, long batchTimeoutMs) {
        this.backendUrl = backendUrl;
        this.batchSize = batchSize;
        this.batchTimeoutMs = batchTimeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void onEvent(GatewayEvent event, long sequence, boolean endOfBatch) {
        if (event.getRawBytes() != null) {
            pendingBatch.add(copyEvent(event));
        }

        boolean sizeReached = pendingBatch.size() >= batchSize;
        boolean timeoutReached = (System.currentTimeMillis() - lastFlushTime) >= batchTimeoutMs;

        if (sizeReached || timeoutReached || endOfBatch) {
            flushBatch();
        }
    }

    /**
     * Flush the pending batch — forward each event to the Jetty backend.
     */
    private void flushBatch() {
        if (pendingBatch.isEmpty()) {
            return;
        }

        for (GatewayEvent evt : pendingBatch) {
            try {
                forwardToBackend(evt);
            } catch (Exception e) {
                log.warn("[BatchDispatcher] Failed to forward request from {}: {}",
                        evt.getRemoteAddr(), e.toString());
            }
        }

        pendingBatch.clear();
        lastFlushTime = System.currentTimeMillis();
    }

    /**
     * Forward a single request to the Jetty WebMVC backend.
     *
     * <p>The raw HTTP bytes are sent as-is to the backend. The backend processes
     * the request through the existing Filter chain (AuthFilter → TraceIdFilter →
     * CellRouterFilter → Controllers).
     */
    private void forwardToBackend(GatewayEvent event) throws Exception {
        // Parse the first line to extract method and path
        String requestLine = new String(event.getRawBytes(), 0,
                Math.min(event.getRawBytes().length, 8192)).split("\r\n")[0];
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            log.warn("[BatchDispatcher] Malformed request from {}", event.getRemoteAddr());
            return;
        }

        String method = parts[0];
        String path = parts[1];
        URI target = URI.create(backendUrl + path);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(target)
                .method(method,
                        HttpRequest.BodyPublishers.ofByteArray(event.getRawBytes()))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<byte[]> response = httpClient.send(httpRequest,
                HttpResponse.BodyHandlers.ofByteArray());

        log.debug("[BatchDispatcher] {} {} → {} ({} bytes)",
                method, path, response.statusCode(),
                response.body() != null ? response.body().length : 0);
    }

    /**
     * Copy event data — the Disruptor reuses the slot, so we must copy
     * before buffering for batch dispatch.
     */
    private GatewayEvent copyEvent(GatewayEvent src) {
        GatewayEvent copy = new GatewayEvent();
        if (src.getRawBytes() != null) {
            copy.setRawBytes(src.getRawBytes().clone());
        }
        copy.setRemoteAddr(src.getRemoteAddr());
        copy.setTimestamp(src.getTimestamp());
        return copy;
    }

    /**
     * Cleanup resources.
     */
    public void shutdown() {
        flushBatch();
    }
}
