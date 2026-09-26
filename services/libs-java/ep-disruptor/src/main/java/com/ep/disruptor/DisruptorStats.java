package com.ep.disruptor;

/**
 * D-171: Reactivated for data-platform ingest pipeline (SDD §5.5 Worker
 * Separation Pattern). Snapshot of RingBuffer published/dropped/consumed
 * counters for monitoring the ingest pipeline.
 */
public record DisruptorStats(String name, long bufferSize, long published, long dropped, long consumed) {
    public long backlog() { return published - consumed; }
}
