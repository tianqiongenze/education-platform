package com.ep.bff.edge;

import io.netty.channel.Channel;
import java.util.Map;

/**
 * Disruptor event for the gateway ingress RingBuffer (SDD §5.2.1).
 *
 * <p>D-216 (audit P0-1/P0-2): the event carries the request in STRUCTURED
 * form (method/path/headers/body) plus the client {@link Channel} so the
 * dispatcher forwards a correctly rebuilt request and writes the REAL backend
 * response back to the client. The old design shipped raw request bytes as
 * the body (headers lost, Content-Length wrong) and answered every client
 * with a canned 202.
 * The Disruptor pre-allocates these objects and reuses slots via the translator
 * pattern — no per-request allocation on the hot path.
 *
 * <p>Event fields:
 * <ul>
 *   <li>{@code remoteAddr} — client IP for logging/rate-limiting</li>
 *   <li>{@code timestamp} — request arrival time (nanos)</li>
 * </ul>
 */
public final class GatewayEvent {

    private String method;
    private String path;
    private Map<String, String> headers;
    private byte[] body;
    private String remoteAddr;
    private long timestamp;
    /** Client channel for async response write-back (writeAndFlush is thread-safe). */
    private Channel channel;

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public byte[] getBody() { return body; }
    public void setBody(byte[] body) { this.body = body; }

    public String getRemoteAddr() { return remoteAddr; }
    public void setRemoteAddr(String remoteAddr) { this.remoteAddr = remoteAddr; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public Channel getChannel() { return channel; }
    public void setChannel(Channel channel) { this.channel = channel; }

    /**
     * Factory method for Disruptor EventFactory — creates a blank slot.
     */
    public static GatewayEvent factory() {
        return new GatewayEvent();
    }
}
