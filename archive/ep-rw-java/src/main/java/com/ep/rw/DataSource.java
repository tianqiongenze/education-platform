package com.ep.rw;
import java.lang.annotation.*;

/**
 * Read-write-splitting routing annotation.
 *
 * @deprecated D-094 (2026-08-08): ep-rw-java has 0 consumers across the
 * monorepo. Deprecated in place (NOT deleted) for future reuse.
 * See {@code services/ep-rw-java/DEPRECATED.md}.
 */
// TODO D-120: Remove after migration complete. Verified zero callers across
// the monorepo (no service imports com.ep.rw.*, no pom.xml declares a
// dependency). Module retained for future read-write-splitting reuse; remove
// this entire module once reuse is confirmed unnecessary.
@Deprecated(forRemoval = false, since = "D-094")
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DataSource {
    DataSourceType value() default DataSourceType.WRITE;
    /** 强制走 Primary，防读后写不一致 */
    boolean consistent() default false;
    /** 路由到 TiFlash 列存（OLAP） */
    boolean tiflash() default false;
    /**
     * D-041: 强制走 TiDB Follower Read（跨单元读）。
     * 启用后路由到 FOLLOWER_READ 数据源，利用 TiDB follower-read
     * 策略在异地多活场景下就近读取 follower 副本，降低跨地域延迟。
     */
    boolean followerRead() default false;
}
