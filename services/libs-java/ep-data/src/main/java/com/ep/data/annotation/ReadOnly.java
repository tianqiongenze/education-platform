package com.ep.data.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * D-216: Route the annotated method to the read pool. Applied even outside a
 * Spring transaction, which is where plain {@code @Transactional(readOnly=true)}
 * cannot help (routing only keys off the tx flag). Implemented by
 * {@code ReadOnlyAspect} via RoutingContextHolder.
 *
 * <p>Use for hot read paths: catalog listings, dashboards, analytics queries,
 * vector-search metadata fetches. Do NOT use after a write in the same request
 * unless read-your-own-writes is not required.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ReadOnly {
}
