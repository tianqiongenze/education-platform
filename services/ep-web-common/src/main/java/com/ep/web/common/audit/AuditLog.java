package com.ep.web.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Risk-control P1-8: marks a method as a security-relevant business action
 * whose invocation must be audit-logged (who did what to which target, with
 * what outcome and how long it took).
 *
 * <p>Intercepted by {@link AuditLogAspect}, which writes one structured JSON
 * entry per invocation to the {@code AUDIT} logger (SLF4J). Typical mount
 * points: order refund, user freeze, course publish — money-moving and
 * account-state-changing operations.</p>
 *
 * <pre>{@code
 * @AuditLog(value = "ORDER_REFUND", target = "#orderId")
 * public void refundOrder(Long orderId, String reason) { ... }
 * }</pre>
 *
 * <p>{@link #target()} may name a SpEL expression evaluated against the method
 * arguments (variables: every parameter by name, plus {@code #args[0..n]}).
 * The default (blank) uses the first argument — the usual aggregate id.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AuditLog {

    /** Short, stable action code, e.g. {@code ORDER_REFUND}, {@code USER_FREEZE}. */
    String value();

    /**
     * Optional SpEL expression identifying the target resource, evaluated with
     * the method parameters as variables ({@code #orderId}, {@code #args[0]}).
     * Blank (default) = the first argument. Keep expressions cheap.
     */
    String target() default "";
}
