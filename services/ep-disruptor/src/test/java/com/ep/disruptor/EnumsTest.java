package com.ep.disruptor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnumsTest {
    @Test void publishResultHasPublishedAndDropped() {
        assertEquals(2, PublishResult.values().length);
        assertSame(PublishResult.PUBLISHED, PublishResult.valueOf("PUBLISHED"));
        assertSame(PublishResult.DROPPED, PublishResult.valueOf("DROPPED"));
    }
    @Test void waitStrategyTypeHasAllFive() {
        assertEquals(5, WaitStrategyType.values().length);
        assertNotNull(WaitStrategyType.valueOf("BLOCKING"));
        assertNotNull(WaitStrategyType.valueOf("YIELDING"));
    }
}
