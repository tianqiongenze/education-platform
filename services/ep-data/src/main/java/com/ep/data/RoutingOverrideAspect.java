package com.ep.data;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

/**
 * D-216: Applies {@code @ReadOnly} / {@code @WriteRoute} by pushing a
 * scope-bounded routing override around the advised call. The override wins
 * over the transactional flag (see EpRoutingDataSource decision chain) and is
 * restored on exit — exception-safe, nested-safe.
 */
@Aspect
public class RoutingOverrideAspect {

    @Pointcut("@annotation(com.ep.data.annotation.ReadOnly)")
    public void readOnlyMethod() {
    }

    @Pointcut("@annotation(com.ep.data.annotation.WriteRoute)")
    public void writeRouteMethod() {
    }

    @Around("readOnlyMethod()")
    public Object aroundReadOnly(ProceedingJoinPoint pjp) throws Throwable {
        try (RoutingContextHolder.Scope ignored = RoutingContextHolder.forceRead()) {
            return pjp.proceed();
        }
    }

    @Around("writeRouteMethod()")
    public Object aroundWriteRoute(ProceedingJoinPoint pjp) throws Throwable {
        try (RoutingContextHolder.Scope ignored = RoutingContextHolder.forceWrite()) {
            return pjp.proceed();
        }
    }
}
