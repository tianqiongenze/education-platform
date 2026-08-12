package com.ep.grading.ide.yjs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-186: YjsSyncHandler tests — verifies the binary protocol message
 * handling for each y-websocket message type.
 */
class YjsSyncHandlerTest {

    private YjsDocument document;
    private YjsSyncHandler handler;

    @BeforeEach
    void setUp() {
        document = new YjsDocument("test-doc");
        handler = new YjsSyncHandler(document);
    }

    @Test
    void sync_step1_replies_with_snapshot() {
        document.applyUpdate(bytes("state"));
        // SYNC_STEP1 = 0, payload = empty
        byte[] message = frame(YjsDocument.SYNC_STEP1);
        Optional<byte[]> reply = handler.handleMessage(message);
        assertTrue(reply.isPresent());
        // reply type = SYNC_STEP2 (1), payload = snapshot
        assertEquals(YjsDocument.SYNC_STEP2, reply.get()[0]);
        byte[] payload = new byte[reply.get().length - 1];
        System.arraycopy(reply.get(), 1, payload, 0, payload.length);
        assertArrayEquals(bytes("state"), payload);
    }

    @Test
    void sync_step2_applies_update_and_returns_empty() {
        byte[] message = frame(YjsDocument.SYNC_STEP2, bytes("update-1"));
        Optional<byte[]> reply = handler.handleMessage(message);
        assertTrue(reply.isEmpty());
        assertEquals(1, document.getUpdateCount());
        assertArrayEquals(bytes("update-1"), document.getSnapshot());
    }

    @Test
    void update_applies_update_and_returns_empty() {
        byte[] message = frame(YjsDocument.UPDATE, bytes("update-2"));
        Optional<byte[]> reply = handler.handleMessage(message);
        assertTrue(reply.isEmpty());
        assertEquals(1, document.getUpdateCount());
    }

    @Test
    void awareness_update_applies_and_returns_empty() {
        byte[] message = frame(YjsDocument.AWARENESS_UPDATE, bytes("cursor"));
        Optional<byte[]> reply = handler.handleMessage(message);
        assertTrue(reply.isEmpty());
        assertArrayEquals(bytes("cursor"), document.getAwareness());
    }

    @Test
    void awareness_query_replies_with_current_awareness() {
        document.applyAwarenessUpdate(bytes("presence"));
        byte[] message = frame(YjsDocument.AWARENESS_QUERY);
        Optional<byte[]> reply = handler.handleMessage(message);
        assertTrue(reply.isPresent());
        assertEquals(YjsDocument.AWARENESS_UPDATE, reply.get()[0]);
        byte[] payload = new byte[reply.get().length - 1];
        System.arraycopy(reply.get(), 1, payload, 0, payload.length);
        assertArrayEquals(bytes("presence"), payload);
    }

    @Test
    void unknown_message_type_returns_empty() {
        byte[] message = frame((byte) 99, bytes("unknown"));
        Optional<byte[]> reply = handler.handleMessage(message);
        assertTrue(reply.isEmpty());
    }

    @Test
    void null_and_empty_messages_return_empty() {
        assertTrue(handler.handleMessage(null).isEmpty());
        assertTrue(handler.handleMessage(new byte[0]).isEmpty());
    }

    @Test
    void encode_update_prepends_type_byte() {
        byte[] encoded = YjsSyncHandler.encodeUpdate(bytes("u"));
        assertEquals(YjsDocument.UPDATE, encoded[0]);
        byte[] payload = new byte[encoded.length - 1];
        System.arraycopy(encoded, 1, payload, 0, payload.length);
        assertArrayEquals(bytes("u"), payload);
    }

    @Test
    void encode_awareness_prepends_type_byte() {
        byte[] encoded = YjsSyncHandler.encodeAwareness(bytes("a"));
        assertEquals(YjsDocument.AWARENESS_UPDATE, encoded[0]);
    }

    // --- helpers -------------------------------------------------------------

    private static byte[] frame(byte type) {
        return new byte[]{type};
    }

    private static byte[] frame(byte type, byte[] payload) {
        byte[] msg = new byte[1 + payload.length];
        msg[0] = type;
        System.arraycopy(payload, 0, msg, 1, payload.length);
        return msg;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
