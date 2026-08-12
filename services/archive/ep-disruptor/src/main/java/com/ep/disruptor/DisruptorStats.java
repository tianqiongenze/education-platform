package com.ep.disruptor;

/**
 * @deprecated D-094 (2026-08-08): ep-disruptor has 0 consumers across the
 * monorepo. Deprecated in place (NOT deleted) for future reuse.
 * See {@code services/ep-disruptor/DEPRECATED.md}.
 */
// TODO D-120: Remove after migration complete. Verified zero callers.
@Deprecated(forRemoval = false, since = "D-094")
public record DisruptorStats(String name, long bufferSize, long published, long dropped, long consumed) {
    public long backlog() { return published - consumed; }
}
