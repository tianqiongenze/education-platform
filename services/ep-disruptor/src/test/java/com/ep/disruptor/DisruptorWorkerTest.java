package com.ep.disruptor;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DisruptorWorkerTest {
    static class Evt { String payload; }
    private DisruptorWorker<Evt> worker;
    private final Queue<String> consumed = new ConcurrentLinkedQueue<>();

    @BeforeEach void setup() throws Exception {
        worker = DisruptorWorker.<Evt>builder()
            .name("test").bufferSize(1024).producerType(com.lmax.disruptor.dsl.ProducerType.MULTI)
            .waitStrategy(WaitStrategyType.BLOCKING)
            .factory(Evt::new)
            .translator((slot, seq, src) -> slot.payload = src.payload)
            .handler((evt, seq, endOfBatch) -> { if (evt.payload != null) consumed.add(evt.payload); })
            .build();
        worker.start();
    }
    @AfterEach void teardown() { worker.shutdown(); }

    @Test void publishesAndConsumes() throws Exception {
        PublishResult r = worker.publish(make("hi"));
        assertEquals(PublishResult.PUBLISHED, r);
        Thread.sleep(200); // 等消费
        assertTrue(consumed.contains("hi"));
    }
    @Test void dropsWhenFull() throws Exception {
        DisruptorWorker<Evt> tiny = DisruptorWorker.<Evt>builder()
            .name("tiny").bufferSize(2).producerType(com.lmax.disruptor.dsl.ProducerType.SINGLE)
            .waitStrategy(WaitStrategyType.BLOCKING).factory(Evt::new)
            .translator((slot, seq, src) -> slot.payload = src.payload)
            .handler((evt, seq, eob) -> { try { Thread.sleep(1000); } catch (InterruptedException ie) {} })
            .build();
        tiny.start();
        try {
            int dropped = 0;
            for (int i = 0; i < 100; i++) if (tiny.publish(make("x" + i)) == PublishResult.DROPPED) dropped++;
            assertTrue(dropped > 0, "满时应有 drop");
        } finally { tiny.shutdown(); }
    }
    @Test void shutdownDrainsPending() throws Exception {
        for (int i = 0; i < 50; i++) worker.publish(make("d" + i));
        worker.shutdown();
        TimeUnit.MILLISECONDS.sleep(50);
        // shutdown 阻塞至消费完成（Disruptor 4.x halt）
        assertTrue(consumed.size() >= 40, "shutdown 应排空, 实际 " + consumed.size());
    }
    @Test void statsReportsBuffersAndCounts() throws Exception {
        worker.publish(make("s"));
        Thread.sleep(200);
        DisruptorStats s = worker.stats();
        assertEquals("test", s.name());
        assertEquals(1024L, s.bufferSize());
        assertTrue(s.published() >= 1);
        assertTrue(s.consumed() >= 1);
    }

    private Evt make(String p) { Evt e = new Evt(); e.payload = p; return e; }
}
