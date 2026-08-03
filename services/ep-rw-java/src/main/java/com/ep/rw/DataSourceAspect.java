package com.ep.rw;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;

@Component
@Aspect
public class DataSourceAspect {
    @Around("@annotation(com.ep.rw.DataSource)")
    public Object around(ProceedingJoinPoint pjp, DataSource ds) throws Throwable {
        DataSourceType prev = RoutingHolder.get();
        boolean forceWrite = ds.consistent() || ds.tiflash() && false; // tiflash 通过 SQL hint，仍走读池但本版简化
        DataSourceType target = ds.consistent() ? DataSourceType.WRITE : ds.value();
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
