package com.ep.rw;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;

/**
 * @deprecated D-094 (2026-08-08): ep-rw-java has 0 consumers across the
 * monorepo. Deprecated in place (NOT deleted) for future reuse.
 * See {@code services/ep-rw-java/DEPRECATED.md}.
 */
// TODO D-120: Remove after migration complete. Verified zero callers.
@Deprecated(forRemoval = false, since = "D-094")
@Component
@Aspect
public class DataSourceAspect {
    @Around("@annotation(com.ep.rw.DataSource)")
    public Object around(ProceedingJoinPoint pjp, DataSource ds) throws Throwable {
        DataSourceType prev = RoutingHolder.get();
        // D-041: followerRead 优先于 value()，支持跨单元 TiDB follower-read
        DataSourceType target;
        if (ds.consistent()) {
            target = DataSourceType.WRITE;
        } else if (ds.followerRead()) {
            target = DataSourceType.FOLLOWER_READ;
        } else {
            target = ds.value();
        }
        if (target == DataSourceType.WRITE) RoutingHolder.clear(); // 写操作清残留防脏读
        else RoutingHolder.set(target);
        try {
            return pjp.proceed();
        } finally {
            // WRITE always clears any residue (防脏读); READ restores prev to support nested calls.
            if (target == DataSourceType.WRITE || prev == null) RoutingHolder.clear();
            else RoutingHolder.set(prev);
        }
    }
}
