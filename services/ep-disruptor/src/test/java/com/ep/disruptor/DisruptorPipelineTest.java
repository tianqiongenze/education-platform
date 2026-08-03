package com.ep.disruptor;
import org.junit.jupiter.api.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DisruptorPipelineTest {
    static class E { String s; }
    private DisruptorPipeline pipe;
    private final ConcurrentLinkedQueue<String> out = new ConcurrentLinkedQueue<>();

    @BeforeEach void setup() { pipe = DisruptorPipeline.named("pipe"); }
    @AfterEach void teardown() { pipe.shutdown(); }

    @Test void twoStagePipelineForwardsToLast() throws Exception {
        pipe.<E>stage("in", E::new, (slot,seq,src)->slot.s=src.s, (e,s,eob)->pipe.forward("in", e))
            .stage("out", E::new, (slot,seq,src)->slot.s=src.s, (e,s,eob)->out.add(e.s))
            .start();
        E msg = new E(); msg.s = "flow";
        pipe.publish("in", msg);
        TimeUnit.MILLISECONDS.sleep(300);
        assertTrue(out.contains("flow"));
    }
}
