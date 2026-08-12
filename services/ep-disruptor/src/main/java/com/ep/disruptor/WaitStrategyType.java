package com.ep.disruptor;
import com.lmax.disruptor.*;

/**
 * D-171: Reactivated for data-platform ingest pipeline (SDD §5.5 Worker
 * Separation Pattern). Disruptor wait strategy selector for the ingest
 * RingBuffer.
 */
public enum WaitStrategyType {
    BLOCKING, SLEEPING, YIELDING, BUSY_SPIN, LITE_BLOCKING;
    public WaitStrategy toWaitStrategy() {
        return switch (this) {
            case BLOCKING -> new BlockingWaitStrategy();
            case SLEEPING -> new SleepingWaitStrategy();
            case YIELDING -> new YieldingWaitStrategy();
            case BUSY_SPIN -> new BusySpinWaitStrategy();
            case LITE_BLOCKING -> new LiteBlockingWaitStrategy();
        };
    }
}
