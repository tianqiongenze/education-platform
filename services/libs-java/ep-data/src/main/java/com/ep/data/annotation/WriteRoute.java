package com.ep.data.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * D-216: Force the write pool regardless of transaction flags — the escape
 * hatch for read-your-own-writes: after committing a write, a subsequent
 * read-only transaction annotated {@code @WriteRoute} is guaranteed to see
 * its own write even on a lagging read replica.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface WriteRoute {
}
