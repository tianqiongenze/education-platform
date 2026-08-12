package com.ep.grading.ide.yjs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * D-186: Yjs binary sync protocol handler.
 * <p>
 * Implements the y-websocket sync protocol message types so the server can
 * interpret inbound binary frames and decide whether to reply or broadcast.
 * <p>
 * Protocol (y-websocket v1):
 * <pre>
 *   message type (1 byte) | payload
 * </pre>
 * Message types:
 * <ul>
 *   <li>{@code 0} SYNC_STEP1 — client requests the server's state vector</li>
 *   <li>{@code 1} SYNC_STEP2 — client sends missing updates to the server</li>
 *   <li>{@code 2} AWARENESS_UPDATE — presence/cursor state</li>
 *   <li>{@code 3} AWARENESS_QUERY — client requests current awareness</li>
 *   <li>{@code 4} UPDATE — a regular document update to broadcast</li>
 * </ul>
 * <p>
 * The handler does NOT implement CRDT merging; it treats updates as opaque
 * blobs appended to the {@link YjsDocument} log and replies with the current
 * snapshot when asked. This mirrors the approach documented in
 * {@link YjsDocument}'s Iron Law 8 decision record.
 */
public class YjsSyncHandler {

    private final YjsDocument document;

    public YjsSyncHandler(YjsDocument document) {
        this.document = document;
    }

    /**
     * Handle an inbound binary message. Returns an Optional containing the
     * bytes to send back to the sender (if any), or empty when no reply is
     * needed (e.g. for UPDATE / AWARENESS_UPDATE which are broadcast by the
     * room, not answered directly).
     *
     * @param message raw WebSocket binary payload
     * @return optional reply bytes for the sender
     */
    public Optional<byte[]> handleMessage(byte[] message) {
        if (message == null || message.length == 0) {
            return Optional.empty();
        }
        int type = message[0] & 0xFF;
        byte[] payload = new byte[message.length - 1];
        System.arraycopy(message, 1, payload, 0, payload.length);

        return switch (type) {
            case YjsDocument.SYNC_STEP1 -> Optional.of(buildSyncStep1Reply());
            case YjsDocument.SYNC_STEP2 -> {
                document.applyUpdate(payload);
                yield Optional.empty();
            }
            case YjsDocument.AWARENESS_UPDATE -> {
                document.applyAwarenessUpdate(payload);
                yield Optional.empty();
            }
            case YjsDocument.AWARENESS_QUERY -> Optional.of(buildAwarenessReply());
            case YjsDocument.UPDATE -> {
                document.applyUpdate(payload);
                yield Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    /** Build a SYNC_STEP1 reply containing the current document snapshot. */
    private byte[] buildSyncStep1Reply() {
        byte[] snapshot = document.getSnapshot();
        // Reply type = SYNC_STEP2 (server sends its state as an update)
        return prependType(YjsDocument.SYNC_STEP2, snapshot);
    }

    /** Build an AWARENESS_QUERY reply containing the current awareness state. */
    private byte[] buildAwarenessReply() {
        byte[] awareness = document.getAwareness();
        return prependType(YjsDocument.AWARENESS_UPDATE, awareness);
    }

    private static byte[] prependType(byte type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 + payload.length);
        out.write(type);
        try {
            out.write(payload);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    /** Convenience: encode an outbound UPDATE frame for broadcasting. */
    public static byte[] encodeUpdate(byte[] update) {
        return prependType(YjsDocument.UPDATE, update);
    }

    /** Convenience: encode an outbound AWARENESS_UPDATE frame. */
    public static byte[] encodeAwareness(byte[] awareness) {
        return prependType(YjsDocument.AWARENESS_UPDATE, awareness);
    }
}
