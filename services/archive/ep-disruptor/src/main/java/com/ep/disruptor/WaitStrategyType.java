package com.ep.disruptor;
import com.lmax.disruptor.*;

/**
 * @deprecated D-094 (2026-08-08): ep-disruptor has 0 consumers across the
 * monorepo. Deprecated in place (NOT deleted) for future reuse.
 * See {@code services/ep-disruptor/DEPRECATED.md}.
 */
// TODO D-120: Remove after migration complete. Verified zero callers.
@Deprecated(forRemoval = false, since = "D-094")
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
