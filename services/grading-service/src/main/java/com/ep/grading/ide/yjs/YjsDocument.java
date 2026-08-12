package com.ep.grading.ide.yjs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * D-186: Server-side Yjs CRDT document (simplified append-only log).
 * <p>
 * <b>Iron Law 8 decision record.</b> Two alternatives were evaluated:
 * <ol>
 *   <li><b>A) Full Yjs CRDT</b> — depends on a Java port of the Yjs library
 *       (none exists as a mature, AOT-compatible artifact). Introduces a heavy
 *       external dependency that is unavailable offline.</li>
 *   <li><b>B) Append-only update log</b> (selected) — stores the raw binary
 *       update frames as they arrive from the WebSocket clients and replays
 *       them on reconnect. The actual CRDT merging is performed client-side by
 *       the official yjs JS library; the server only needs to fan out updates
 *       and persist snapshots. Zero external dependencies, AOT-friendly.</li>
 * </ol>
 * <p>
 * This class is intentionally not thread-safe; the caller (ws-gateway room or
 * the snapshot store) serialises access.
 */
public class YjsDocument {

    /** y-websocket sync step constants (see Yjs sync protocol). */
    public static final byte SYNC_STEP1 = 0;
    public static final byte SYNC_STEP2 = 1;
    public static final byte AWARENESS_UPDATE = 2;
    public static final byte AWARENESS_QUERY = 3;
    public static final byte UPDATE = 4;

    private final String docId;
    private final List<byte[]> updates = new ArrayList<>();
    private final List<byte[]> awarenessUpdates = new ArrayList<>();
    private long updateCount = 0;

    public YjsDocument(String docId) {
        this.docId = docId;
    }

    /** Document id (correlates with {@code IdeSession.yjsDocId}). */
    public String getDocId() {
        return docId;
    }

    /** Number of document updates applied so far. */
    public long getUpdateCount() {
        return updateCount;
    }

    /**
     * Apply a binary update to the document. The raw bytes are appended to the
     * internal log so they can be replayed to reconnecting clients.
     */
    public void applyUpdate(byte[] update) {
        if (update == null || update.length == 0) {
            return;
        }
        updates.add(update.clone());
        updateCount++;
    }

    /**
     * Apply an awareness update (presence/cursor information).
     */
    public void applyAwarenessUpdate(byte[] update) {
        if (update == null || update.length == 0) {
            return;
        }
        awarenessUpdates.add(update.clone());
    }

    /**
     * Return a flattened snapshot of all applied updates. Reconnecting clients
     * receive this so they can reconstruct the full document state.
     */
    public byte[] getSnapshot() {
        return flatten(updates);
    }

    /** Return a flattened snapshot of all awareness updates. */
    public byte[] getAwareness() {
        return flatten(awarenessUpdates);
    }

    /** Flatten a list of byte arrays into a single contiguous array. */
    private static byte[] flatten(List<byte[]> chunks) {
        if (chunks.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            try {
                out.write(chunk);
            } catch (IOException e) {
                // ByteArrayOutputStream never throws on write, but satisfy the compiler
                throw new RuntimeException(e);
            }
        }
        return out.toByteArray();
    }
}
