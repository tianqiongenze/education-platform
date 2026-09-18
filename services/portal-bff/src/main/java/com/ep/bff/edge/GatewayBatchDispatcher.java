package com.ep.bff.edge;

import com.lmax.disruptor.EventHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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

    /** Hop-by-hop headers never forwarded to the backend (HttpClient recomputes them). */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "host", "content-length", "connection", "keep-alive",
            "transfer-encoding", "upgrade", "proxy-connection", "te", "trailer");

    private static final int MAX_INFLIGHT_DEFAULT = 256;

    private final String backendUrl;
    private final int batchSize;
    private final long batchTimeoutMs;
    private final HttpClient httpClient;
    private final List<GatewayEvent> pendingBatch = new ArrayList<>();
    private long lastFlushTime = System.currentTimeMillis();
    /** Caps concurrent in-flight backend calls (backpressure beyond the RingBuffer). */
    private final Semaphore inflight;
    /** Virtual threads for response-completion continuations. */
    private final ExecutorService completionExecutor;

    public GatewayBatchDispatcher(String backendUrl, int batchSize, long batchTimeoutMs) {
        this(backendUrl, batchSize, batchTimeoutMs, MAX_INFLIGHT_DEFAULT);
    }

    public GatewayBatchDispatcher(String backendUrl, int batchSize, long batchTimeoutMs, int maxInflight) {
        this.backendUrl = backendUrl;
        this.batchSize = batchSize;
        this.batchTimeoutMs = batchTimeoutMs;
        this.inflight = new Semaphore(maxInflight);
        this.completionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @Override
    public void onEvent(GatewayEvent event, long sequence, boolean endOfBatch) {
        if (event.getMethod() != null && event.getPath() != null) {
            pendingBatch.add(copyEvent(event));
        }

        boolean sizeReached = pendingBatch.size() >= batchSize;
        boolean timeoutReached = (System.currentTimeMillis() - lastFlushTime) >= batchTimeoutMs;

        if (sizeReached || timeoutReached || endOfBatch) {
            flushBatch();
        }
    }

    /**
     * Flush the pending batch — dispatch each event ASYNCHRONOUSLY; this
     * method never blocks on a backend call, so the RingBuffer consumer
     * keeps draining even when the backend is slow.
     */
    private void flushBatch() {
        if (pendingBatch.isEmpty()) {
            return;
        }

        for (GatewayEvent evt : pendingBatch) {
            dispatchAsync(evt);
        }

        pendingBatch.clear();
        lastFlushTime = System.currentTimeMillis();
    }

    /**
     * Rebuild the backend request from the event's structured fields and
     * forward asynchronously; the backend response is written back to the
     * event's client channel. Over-capacity events get a 503 (never dropped
     * silently, never a fake 202).
     */
    void dispatchAsync(GatewayEvent event) {
        Channel client = event.getChannel();
        if (event.getMethod() == null || event.getPath() == null) {
            log.warn("[BatchDispatcher] Malformed event from {}", event.getRemoteAddr());
            writeResponse(client, 400, "{\"error\":\"malformed request\"}".getBytes(), "application/json");
            return;
        }
        if (!inflight.tryAcquire()) {
            log.warn("[BatchDispatcher] in-flight cap reached — 503 for {} {}",
                    event.getMethod(), event.getPath());
            writeResponse(client, 503,
                    "{\"error\":\"gateway saturated\",\"status\":\"503\"}".getBytes(), "application/json");
            return;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(backendUrl + event.getPath()))
                .timeout(Duration.ofSeconds(30))
                .setHeader("X-Forwarded-For",
                        event.getRemoteAddr() == null ? "unknown" : event.getRemoteAddr());
        // rebuild end-to-end headers (hop-by-hop excluded)
        if (event.getHeaders() != null) {
            for (Map.Entry<String, String> h : event.getHeaders().entrySet()) {
                if (!HOP_BY_HOP.contains(h.getKey().toLowerCase())) {
                    builder.setHeader(h.getKey(), h.getValue());
                }
            }
        }
        byte[] body = event.getBody() == null ? new byte[0] : event.getBody();
        builder.method(event.getMethod(), HttpRequest.BodyPublishers.ofByteArray(body));

        httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                .whenCompleteAsync((response, err) -> {
                    try {
                        if (err != null) {
                            log.warn("[BatchDispatcher] backend call failed ({} {}): {}",
                                    event.getMethod(), event.getPath(), err.toString());
                            writeResponse(client, 502,
                                    "{\"error\":\"backend unavailable\",\"status\":\"502\"}".getBytes(),
                                    "application/json");
                        } else {
                            byte[] respBody = response.body() == null ? new byte[0] : response.body();
                            String contentType = response.headers()
                                    .firstValue("Content-Type").orElse("application/octet-stream");
                            log.debug("[BatchDispatcher] {} {} → {} ({} bytes)",
                                    event.getMethod(), event.getPath(), response.statusCode(), respBody.length);
                            writeResponse(client, response.statusCode(), respBody, contentType);
                        }
                    } finally {
                        inflight.release();
                    }
                }, completionExecutor);
    }

    /** Write an HTTP response back to the client channel (thread-safe). */
    private void writeResponse(Channel channel, int status, byte[] body, String contentType) {
        if (channel == null || !channel.isActive()) {
            return; // client went away — nothing to answer
        }
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status),
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        channel.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Copy event data — the Disruptor reuses the slot, so we must copy
     * before buffering for batch dispatch.
     */
    private GatewayEvent copyEvent(GatewayEvent src) {
        GatewayEvent copy = new GatewayEvent();
        copy.setMethod(src.getMethod());
        copy.setPath(src.getPath());
        copy.setHeaders(src.getHeaders());
        if (src.getBody() != null) {
            copy.setBody(src.getBody().clone());
        }
        copy.setRemoteAddr(src.getRemoteAddr());
        copy.setTimestamp(src.getTimestamp());
        copy.setChannel(src.getChannel());
        return copy;
    }

    /**
     * Cleanup resources.
     */
    public void shutdown() {
        flushBatch();
        completionExecutor.shutdown();
        try {
            completionExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
