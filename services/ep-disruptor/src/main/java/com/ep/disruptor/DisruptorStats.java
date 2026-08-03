package com.ep.disruptor;
public record DisruptorStats(String name, long bufferSize, long published, long dropped, long consumed) {
    public long backlog() { return published - consumed; }
}
