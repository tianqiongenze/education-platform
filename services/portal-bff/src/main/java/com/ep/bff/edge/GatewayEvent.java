package com.ep.bff.edge;

/**
 * Disruptor event for the gateway ingress RingBuffer (SDD §5.2.1).
 *
 * <p>Each event carries the raw HTTP request bytes received by NettyEdgeServer.
 * The Disruptor pre-allocates these objects and reuses slots via the translator
 * pattern — no per-request allocation on the hot path.
 *
 * <p>Event fields:
 * <ul>
 *   <li>{@code rawBytes} — full HTTP request bytes (method + headers + body)</li>
 *   <li>{@code remoteAddr} — client IP for logging/rate-limiting</li>
 *   <li>{@code timestamp} — request arrival time (nanos)</li>
 * </ul>
 */
public final class GatewayEvent {

    private byte[] rawBytes;
    private String remoteAddr;
    private long timestamp;

    public byte[] getRawBytes() { return rawBytes; }
    public void setRawBytes(byte[] rawBytes) { this.rawBytes = rawBytes; }

    public String getRemoteAddr() { return remoteAddr; }
    public void setRemoteAddr(String remoteAddr) { this.remoteAddr = remoteAddr; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    /**
     * Factory method for Disruptor EventFactory — creates a blank slot.
     */
    public static GatewayEvent factory() {
        return new GatewayEvent();
    }
}
