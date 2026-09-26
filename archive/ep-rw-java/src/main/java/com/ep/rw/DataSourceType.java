package com.ep.rw;

/**
 * Read-write separation routing keys.
 *
 * <p>D-041: FOLLOWER_READ routes to TiDB follower nodes for cross-cell
 * queries. When {@code ep.rw.follower-read.enabled=true}, READ operations
 * in read-heavy flows can be upgraded to FOLLOWER_READ via
 * {@link DataSource#value()} to leverage TiDB's follower-read strategy
 * for multi-region disaster tolerance.
 *
 * @deprecated D-094 (2026-08-08): ep-rw-java has 0 consumers across the
 * monorepo — no service imports {@code com.ep.rw.*}, no {@code pom.xml}
 * declares a dependency, and the artifact is not in {@code .m2}. The
 * module is deprecated in place (NOT deleted) for future BU expansion.
 * See {@code services/ep-rw-java/DEPRECATED.md}. Re-activation: add a
 * {@code <dependency>} on {@code com.ep:ep-rw-java:1.0.0} to the target
 * service and remove this annotation.
 */
// TODO D-120: Remove after migration complete. Verified zero callers.
@Deprecated(forRemoval = false, since = "D-094")
public enum DataSourceType {
    WRITE,
    READ,
    FOLLOWER_READ
}
