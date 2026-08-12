package com.ep.grading.ide.yjs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-186: YjsSnapshotStore tests — mock {@link StringRedisTemplate} to verify
 * incremental snapshot persistence and loading without a live Redis.
 */
@SuppressWarnings("unchecked")
class YjsSnapshotStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ObjectProvider<StringRedisTemplate> redisProvider;
    private YjsSnapshotStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        store = new YjsSnapshotStore(redisProvider, new ObjectMapper());
    }

    @Test
    void save_snapshot_skips_until_interval_reached() {
        YjsDocument doc = new YjsDocument("doc-1");
        doc.applyUpdate(bytes("u1"));
        // updates 1..99 should not trigger a snapshot
        for (int i = 0; i < 99; i++) {
            assertFalse(store.saveSnapshot("doc-1", doc));
        }
        verify(valueOps, never()).set(anyString(), anyString(), any());
        // update 100 triggers a snapshot — set(key,value,Duration) returns void in Spring Data Redis 4.x
        assertTrue(store.saveSnapshot("doc-1", doc));
        verify(valueOps).set(
                eq(YjsSnapshotStore.SNAPSHOT_KEY_PREFIX + "doc-1"),
                anyString(),
                eq(YjsSnapshotStore.SNAPSHOT_TTL));
    }

    @Test
    void save_snapshot_returns_false_when_redis_unavailable() {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        YjsDocument doc = new YjsDocument("doc-2");
        // fast-forward to the 100th update
        for (int i = 0; i < 99; i++) {
            store.saveSnapshot("doc-2", doc);
        }
        assertFalse(store.saveSnapshot("doc-2", doc));
    }

    @Test
    void save_snapshot_returns_false_for_blank_doc_id() {
        assertFalse(store.saveSnapshot("", new YjsDocument("")));
        assertFalse(store.saveSnapshot(null, new YjsDocument("x")));
    }

    @Test
    void load_snapshot_returns_present_when_key_exists() throws Exception {
        YjsDocument doc = new YjsDocument("doc-3");
        doc.applyUpdate(bytes("snapshot-bytes"));
        byte[] snapshot = doc.getSnapshot();
        String b64 = Base64.getEncoder().encodeToString(snapshot);
        String json = new ObjectMapper().writeValueAsString(Map.of(
                "docId", "doc-3",
                "seq", 100,
                "updateCount", 100L,
                "snapshot", b64));
        when(valueOps.get(YjsSnapshotStore.SNAPSHOT_KEY_PREFIX + "doc-3"))
                .thenReturn(json);

        Optional<YjsSnapshotStore.LoadedSnapshot> loaded = store.loadSnapshot("doc-3");

        assertTrue(loaded.isPresent());
        assertEquals("doc-3", loaded.get().docId());
        assertEquals(100L, loaded.get().seq());
        assertEquals(100L, loaded.get().updateCount());
        assertArrayEquals(snapshot, loaded.get().snapshot());
    }

    @Test
    void load_snapshot_returns_empty_when_key_missing() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertTrue(store.loadSnapshot("missing").isEmpty());
    }

    @Test
    void load_snapshot_returns_empty_when_redis_unavailable() {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        assertTrue(store.loadSnapshot("doc-4").isEmpty());
    }

    @Test
    void load_snapshot_returns_empty_for_blank_doc_id() {
        assertTrue(store.loadSnapshot("").isEmpty());
        assertTrue(store.loadSnapshot(null).isEmpty());
    }

    @Test
    void save_then_load_roundtrip_preserves_snapshot() {
        YjsDocument doc = new YjsDocument("doc-rt");
        doc.applyUpdate(bytes("roundtrip-data"));
        // set(key,value,Duration) returns void in Spring Data Redis 4.x
        // fast-forward to update 100
        for (int i = 0; i < 99; i++) {
            store.saveSnapshot("doc-rt", doc);
        }
        assertTrue(store.saveSnapshot("doc-rt", doc));

        // simulate Redis holding the JSON we just wrote
        when(valueOps.get(YjsSnapshotStore.SNAPSHOT_KEY_PREFIX + "doc-rt"))
                .thenAnswer(inv -> {
                    try {
                        String b64 = Base64.getEncoder().encodeToString(doc.getSnapshot());
                        return new ObjectMapper().writeValueAsString(Map.of(
                                "docId", "doc-rt",
                                "seq", 100,
                                "updateCount", doc.getUpdateCount(),
                                "snapshot", b64));
                    } catch (Exception e) {
                        return null;
                    }
                });

        Optional<YjsSnapshotStore.LoadedSnapshot> loaded = store.loadSnapshot("doc-rt");
        assertTrue(loaded.isPresent());
        assertArrayEquals(doc.getSnapshot(), loaded.get().snapshot());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
