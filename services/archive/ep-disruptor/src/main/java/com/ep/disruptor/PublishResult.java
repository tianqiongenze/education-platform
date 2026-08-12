package com.ep.disruptor;

/**
 * @deprecated D-094 (2026-08-08): ep-disruptor has 0 consumers across the
 * monorepo — no service imports {@code com.ep.disruptor.*}, no
 * {@code pom.xml} declares a dependency, and the artifact is not in
 * {@code .m2}. Deprecated in place (NOT deleted) for future reuse.
 * See {@code services/ep-disruptor/DEPRECATED.md}.
 */
// TODO D-120: Remove after migration complete. Verified zero callers.
@Deprecated(forRemoval = false, since = "D-094")
public enum PublishResult { PUBLISHED, DROPPED }
