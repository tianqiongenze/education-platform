package com.ep.disruptor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DisruptorStatsTest {
    @Test void recordHoldsValues() {
        DisruptorStats s = new DisruptorStats("n", 65536L, 100L, 5L, 95L);
        assertEquals("n", s.name());
        assertEquals(65536L, s.bufferSize());
        assertEquals(100L, s.published());
        assertEquals(5L, s.dropped());
        assertEquals(95L, s.consumed());
    }
    @Test void backlogIsPublishedMinusConsumed() {
        DisruptorStats s = new DisruptorStats("n", 64L, 100L, 0L, 80L);
        assertEquals(20L, s.backlog());
    }
}
