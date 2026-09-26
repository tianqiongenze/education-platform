package com.ep.disruptor.spring;

import com.ep.disruptor.DisruptorPipeline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-216: registry lifecycle — registration order start, reverse-order stop,
 * stats aggregation across pipelines of ARBITRARY stage depth (verifies the
 * "custom scheduling-queue hierarchy count" requirement end to end).
 */
class DisruptorPipelineRegistryTest {

    static class E {
        String s;
    }

    private final DisruptorPipelineRegistry registry = new DisruptorPipelineRegistry();
    private final ConcurrentLinkedQueue<String> sink = new ConcurrentLinkedQueue<>();

    @AfterEach
    void tearDown() {
        registry.stop();
    }

    @Test
    @DisplayName("pipelines with 2 and 5 custom stage depths run and aggregate stats")
    void pipelinesRunWithDifferentDepths() throws Exception {
        DisruptorPipeline two = DisruptorPipeline.named("two");
        two.<E>stage("a", E::new, (slot, seq, src) -> slot.s = src.s, (e, s, eob) -> sink.add("a:" + e.s))
                .stage("b", E::new, (slot, seq, src) -> slot.s = src.s, (e, s, eob) -> sink.add("b:" + e.s));

        DisruptorPipeline five = DisruptorPipeline.named("five");
        String[] names = {"s1", "s2", "s3", "s4", "s5"};
        for (String n : names) {
            five.<E>stage(n, E::new, (slot, seq, src) -> slot.s = src.s,
                    (e, s, eob) -> sink.add(n + ":" + e.s));
        }

        registry.register(two);
        registry.register(five);
        registry.start();

        assertEquals(2, registry.pipelines().size());
        // forward through every stage of the 5-level pipeline: depth is caller-defined
        E msg = new E();
        msg.s = "x";
        five.publish("s1", msg);
        five.forward("s1", msg);
        five.forward("s2", msg);
        five.forward("s3", msg);
        five.forward("s4", msg);

        long deadline = System.currentTimeMillis() + 3_000;
        while (sink.size() < 5 && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertTrue(sink.size() >= 5, "expected >=5 events, got " + sink);
        // stageNames preserves declaration order across a 5-level chain
        assertEquals(List.of(names), five.stageNames());

        var snapshot = registry.statsSnapshot();
        assertEquals(2, snapshot.size());
        assertTrue(snapshot.get("five").containsKey("s3"));
        assertTrue(snapshot.get("two").containsKey("b"));
    }

    @Test
    @DisplayName("late registration after start() starts the pipeline immediately")
    void lateRegistrationStartsPipeline() throws Exception {
        registry.start();
        DisruptorPipeline p = DisruptorPipeline.named("late");
        p.<E>stage("only", E::new, (slot, seq, src) -> slot.s = src.s,
                (e, s, eob) -> sink.add("only:" + e.s));
        registry.register(p);
        E msg = new E();
        msg.s = "v1";
        p.publish("only", msg);
        long deadline = System.currentTimeMillis() + 3_000;
        while (sink.isEmpty() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertTrue(!sink.isEmpty(), "late-registered pipeline produced no events");
    }

    @Test
    @DisplayName("stats by stage name returns null for unknown stage")
    void statsByNameUnknown() {
        DisruptorPipeline p = DisruptorPipeline.named("q");
        p.<E>stage("known", E::new, (slot, seq, src) -> slot.s = src.s,
                (e, s, eob) -> sink.add("k:" + e.s));
        registry.register(p);
        registry.start();
        assertNotNull(p.stats("known"));
        assertNull(p.stats("nope"));
    }
}
