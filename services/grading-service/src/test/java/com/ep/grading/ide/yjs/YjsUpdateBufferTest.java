package com.ep.grading.ide.yjs;

import com.ep.disruptor.DisruptorStats;
import com.ep.disruptor.PublishResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * D-216: unit tests for {@link YjsUpdateBuffer} — the single Disruptor worker
 * that buffers high-frequency Yjs collaborative-edit updates.
 *
 * <p>Instantiates the buffer directly (the bean is {@code @ConditionalOnProperty},
 * so we bypass Spring) and drives real binary updates through the RingBuffer,
 * asserting they land on the {@link YjsDocumentRegistry} document and trigger
 * the snapshot store.
 */
class YjsUpdateBufferTest {

    private YjsUpdateBuffer buffer;
    private YjsSnapshotStore snapshotStore;
    private YjsDocumentRegistry documentRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        snapshotStore = new YjsSnapshotStore(redisProvider, new ObjectMapper());
        documentRegistry = new YjsDocumentRegistry();

        buffer = new YjsUpdateBuffer(snapshotStore, documentRegistry);
        setField(buffer, "bufferSize", 256);
        setField(buffer, "waitStrategyName", "SLEEPING");
        buffer.init();
    }

    @AfterEach
    void tearDown() {
        if (buffer != null) {
            buffer.shutdown();
        }
    }

    @Test
    @DisplayName("submit returns PUBLISHED when RingBuffer has capacity")
    void submitReturnsPublishedWhenCapacityAvailable() {
        PublishResult result = buffer.submit("doc-1", new byte[]{1, 2, 3});
        assertEquals(PublishResult.PUBLISHED, result);
    }

    @Test
    @DisplayName("submitted update is applied to the registry document")
    void submittedUpdateAppliedToRegistryDocument() throws Exception {
        byte[] update = new byte[]{10, 20, 30};
        buffer.submit("doc-apply", update);
        // Give the consumer thread time to drain.
        Thread.sleep(200);
        YjsDocument document = documentRegistry.get("doc-apply");
        assertNotNull(document, "document should be registered after submit");
        assertTrue(document.getUpdateCount() >= 1, "update count should be >= 1");
    }

    @Test
    @DisplayName("submit returns DROPPED for null docId or update")
    void submitReturnsDroppedForNullOrBlank() {
        assertEquals(PublishResult.DROPPED, buffer.submit(null, new byte[]{1}));
        assertEquals(PublishResult.DROPPED, buffer.submit("doc", null));
        assertEquals(PublishResult.DROPPED, buffer.submit("doc", new byte[0]));
    }

    @Test
    @DisplayName("stats reports the correct worker name and buffer size")
    void statsReportsCorrectNameAndBufferSize() {
        DisruptorStats stats = buffer.stats();
        assertEquals("grading-yjs-updates", stats.name());
        assertEquals(256L, stats.bufferSize());
    }

    @Test
    @DisplayName("multiple submits are all applied (no loss under capacity)")
    void multipleSubmitsAllApplied() throws Exception {
        for (int i = 0; i < 50; i++) {
            buffer.submit("doc-burst", new byte[]{(byte) i});
        }
        Thread.sleep(400);
        YjsDocument document = documentRegistry.get("doc-burst");
        assertNotNull(document);
        assertEquals(50, document.getUpdateCount());
    }

    @Test
    @DisplayName("shutdown drains pending updates without hanging")
    void shutdownDrainsWithoutHanging() {
        for (int i = 0; i < 30; i++) {
            buffer.submit("doc-drain-" + i, new byte[]{(byte) i});
        }
        buffer.shutdown();
        // If we reach here, shutdown completed without hanging.
        DisruptorStats stats = buffer.stats();
        assertTrue(stats.published() >= 30, "all submitted events should be published");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
