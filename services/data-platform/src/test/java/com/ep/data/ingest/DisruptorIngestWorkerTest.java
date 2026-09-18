package com.ep.data.ingest;

import com.ep.data.cdc.CdcEnvelope;
import com.ep.data.cdc.OpType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-216 (audit P0-3): the ingest worker is REAL — events are batch-persisted,
 * failing batches retry then degrade to the fallback chain, and the fallback
 * chain never silently drops an event (disk spool keeps it recoverable).
 */
class DisruptorIngestWorkerTest {

    @TempDir
    Path tmp;

    private static IngestEvent evt(String table) {
        CdcEnvelope env = new CdcEnvelope(OpType.CREATE, System.currentTimeMillis(),
                "cell-bj", table, null, "{\"id\":1}");
        IngestEvent e = new IngestEvent();
        e.setEnvelope(env);
        e.setSourceCell("cell-bj");
        e.setIngestTs(System.currentTimeMillis());
        return e;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JdbcTemplate> provider(JdbcTemplate jdbc) {
        ObjectProvider<JdbcTemplate> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(jdbc);
        return p;
    }

    @Test
    @DisplayName("events accumulate and batch-flush via jdbc.batchUpdate")
    void batchPersist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        org.mockito.Mockito.doReturn(new int[0]).when(jdbc).batchUpdate(anyString(), org.mockito.ArgumentMatchers.anyList());
        DisruptorIngestWorker w = new DisruptorIngestWorker(provider(jdbc), mock(PulsarFallback.class));
        set(w, "batchSize", 3);
        w.onEvent(evt("t1"));
        w.onEvent(evt("t2"));
        verify(jdbc, org.mockito.Mockito.never()).batchUpdate(anyString(), org.mockito.ArgumentMatchers.anyList());
        w.onEvent(evt("t3")); // hits batch size -> immediate flush
        verify(jdbc, atLeastOnce()).batchUpdate(anyString(), org.mockito.ArgumentMatchers.anyList());
        assertEquals(3, w.persistedTotal());
    }

    @Test
    @DisplayName("persist failure after retries degrades the batch to fallback")
    void retryThenFallback() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        org.mockito.Mockito.doThrow(new RuntimeException("tikv unavailable")).when(jdbc)
                .batchUpdate(anyString(), org.mockito.ArgumentMatchers.anyList());
        AtomicInteger fallbackCalls = new AtomicInteger();
        PulsarFallback fb = new PulsarFallback() {
            @Override
            public void publish(IngestEvent event) {
                fallbackCalls.incrementAndGet();
            }
        };
        DisruptorIngestWorker w = new DisruptorIngestWorker(provider(jdbc), fb);
        set(w, "batchSize", 2);
        set(w, "maxRetries", 2);
        w.onEvent(evt("t1"));
        w.onEvent(evt("t2")); // flush -> 2 failed attempts -> 2 events degraded
        assertEquals(2, fallbackCalls.get());
        assertEquals(2, w.fallbackTotal());
    }

    @Test
    @DisplayName("no DataSource: the whole batch degrades instead of vanishing")
    void noDataSourceDegrades() {
        ObjectProvider<JdbcTemplate> empty = provider(null);
        AtomicInteger fallbackCalls = new AtomicInteger();
        PulsarFallback fb = new PulsarFallback() {
            @Override
            public void publish(IngestEvent event) {
                fallbackCalls.incrementAndGet();
            }
        };
        DisruptorIngestWorker w = new DisruptorIngestWorker(empty, fb);
        set(w, "batchSize", 2);
        w.onEvent(evt("t1"));
        w.onEvent(evt("t2"));
        w.flush();
        assertEquals(2, w.fallbackTotal());
        assertEquals(2, fallbackCalls.get());
    }

    private static void set(Object target, String field, int value) {
        set(target, field, (Object) value);
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("PulsarFallback DiskSpool: event lands in spool file and is replayable")
    void diskSpoolRoundTrip() throws Exception {
        PulsarFallback fb = new PulsarFallback();
        set(fb, "pulsarEnabled", false);
        set(fb, "spoolDir", tmp.toString());

        fb.publish(evt("orders"));

        List<String> lines = fb.replay();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"table\":\"orders\""));
    }

}
