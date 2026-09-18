package com.ep.disruptor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-216 (audit P0-4): a poison payload must never kill the consuming thread.
 * The Disruptor default FatalExceptionHandler rethrows inside the consumer,
 * permanently stopping consumption — this suite pins the skip-and-continue
 * behaviour that makes the library safe to rollout platform-wide.
 */
class DisruptorPoisonEventTest {

    static class E {
        String s;
    }

    private DisruptorWorker<E> worker;
    private final ConcurrentLinkedQueue<String> out = new ConcurrentLinkedQueue<>();
    private final AtomicInteger handled = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (worker != null) {
            worker.shutdown();
        }
    }

    @Test
    @DisplayName("handler exception skips the poison event; later events still consumed")
    void poisonEventDoesNotKillConsumer() throws Exception {
        worker = DisruptorWorker.<E>builder()
                .name("poison-test")
                .bufferSize(1024)
                .factory(E::new)
                .translator((slot, seq, src) -> slot.s = src.s)
                .handler((evt, seq, eob) -> {
                    if ("POISON".equals(evt.s)) {
                        throw new IllegalStateException("boom on poison");
                    }
                    out.add(evt.s);
                })
                .build();
        worker.start();

        worker.publish(evt("ok-1"));
        worker.publish(evt("POISON"));
        worker.publish(evt("ok-2"));
        worker.publish(evt("ok-3"));

        long deadline = System.currentTimeMillis() + 5_000;
        while (out.size() < 3 && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        // the poison event was skipped, everything after it still arrived
        assertTrue(out.contains("ok-1"), "out=" + out);
        assertTrue(out.contains("ok-2"), "out=" + out);
        assertTrue(out.contains("ok-3"), "out=" + out);
        assertTrue(worker.failedCount() >= 1, "failure counter should record the poison event");
    }

    @Test
    @DisplayName("translator exception still publishes the slot (no ring-buffer poisoning)")
    void translatorExceptionDoesNotPoisonRingBuffer() throws Exception {
        worker = DisruptorWorker.<E>builder()
                .name("translator-test")
                .bufferSize(1024)
                .factory(E::new)
                .translator((slot, seq, src) -> {
                    if ("BAD".equals(src.s)) {
                        throw new IllegalArgumentException("bad payload");
                    }
                    slot.s = src.s;
                })
                .handler((evt, seq, eob) -> {
                    handled.incrementAndGet();
                    if (evt.s != null) {
                        out.add(evt.s);
                    }
                })
                .build();
        worker.start();

        worker.publish(evt("good-1"));
        worker.publish(evt("BAD"));
        worker.publish(evt("good-2"));

        long deadline = System.currentTimeMillis() + 5_000;
        while (handled.get() < 3 && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        // reserved sequence was still published → 3 slots consumed, no stall
        assertTrue(handled.get() >= 3, "handled=" + handled.get());
        assertTrue(out.contains("good-1") && out.contains("good-2"), "out=" + out);
    }

    private static E evt(String s) {
        E e = new E();
        e.s = s;
        return e;
    }
}
