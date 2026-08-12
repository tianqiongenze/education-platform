package com.ep.grading.ide.yjs;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-186: YjsDocument tests — verifies applyUpdate / getSnapshot / awareness
 * behaviour of the append-only log implementation.
 */
class YjsDocumentTest {

    @Test
    void empty_document_returns_empty_snapshot() {
        YjsDocument doc = new YjsDocument("d1");
        assertEquals(0, doc.getSnapshot().length);
        assertEquals(0, doc.getUpdateCount());
    }

    @Test
    void apply_update_appends_and_increments_count() {
        YjsDocument doc = new YjsDocument("d2");
        doc.applyUpdate(bytes("hello"));
        doc.applyUpdate(bytes("world"));
        assertEquals(2, doc.getUpdateCount());
        // snapshot is the concatenation of all updates
        assertArrayEquals(bytes("helloworld"), doc.getSnapshot());
    }

    @Test
    void apply_update_ignores_null_and_empty() {
        YjsDocument doc = new YjsDocument("d3");
        doc.applyUpdate(null);
        doc.applyUpdate(new byte[0]);
        assertEquals(0, doc.getUpdateCount());
        assertEquals(0, doc.getSnapshot().length);
    }

    @Test
    void apply_awareness_update_appended_separately() {
        YjsDocument doc = new YjsDocument("d4");
        doc.applyUpdate(bytes("doc-update"));
        doc.applyAwarenessUpdate(bytes("awareness-1"));
        doc.applyAwarenessUpdate(bytes("awareness-2"));
        // document snapshot does NOT include awareness
        assertArrayEquals(bytes("doc-update"), doc.getSnapshot());
        // awareness is the concatenation of awareness updates
        assertArrayEquals(bytes("awareness-1awareness-2"), doc.getAwareness());
    }

    @Test
    void doc_id_round_trips() {
        YjsDocument doc = new YjsDocument("my-doc-id");
        assertEquals("my-doc-id", doc.getDocId());
    }

    @Test
    void get_snapshot_returns_clone_not_internal_reference() {
        YjsDocument doc = new YjsDocument("d5");
        doc.applyUpdate(bytes("abc"));
        byte[] snap1 = doc.getSnapshot();
        // mutating the returned array must not affect subsequent snapshots
        snap1[0] = 'X';
        byte[] snap2 = doc.getSnapshot();
        assertEquals('a', snap2[0]);
    }

    @Test
    void protocol_constants_match_y_websocket_spec() {
        assertEquals(0, YjsDocument.SYNC_STEP1);
        assertEquals(1, YjsDocument.SYNC_STEP2);
        assertEquals(2, YjsDocument.AWARENESS_UPDATE);
        assertEquals(3, YjsDocument.AWARENESS_QUERY);
        assertEquals(4, YjsDocument.UPDATE);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
