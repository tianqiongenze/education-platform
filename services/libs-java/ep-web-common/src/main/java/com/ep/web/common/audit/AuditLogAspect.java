package com.ep.web.common.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Risk-control P1-8: {@code @Around} aspect that audit-logs every invocation
 * of a method annotated with {@link AuditLog} — user, tenant, action, target,
 * result (SUCCESS/FAILURE), duration and trace id — as one JSON line on the
 * {@code AUDIT} logger (see {@link AuditLogger}).
 *
 * <p>Failures in the logging path never break the business method: the aspect
 * swallows and warns. The annotated method's own exceptions are recorded as
 * {@code FAILURE} entries and re-thrown unchanged.</p>
 */
@Aspect
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private final AuditLogger auditLogger;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    public AuditLogAspect() {
        this(new AuditLogger());
    }

    /** Test seam: inject a spy/mock logger. */
    public AuditLogAspect(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    @Around("@annotation(auditLog)")
    public Object aroundAuditedMethod(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        long start = System.nanoTime();
        Object targetId = resolveTarget(pjp, auditLog);
        try {
            Object result = pjp.proceed();
            auditLogger.audit(AuditLogger.AuditEntry.of(
                    auditLog.value(), String.valueOf(targetId),
                    AuditLogger.AuditEntry.SUCCESS,
                    elapsedMs(start), null));
            return result;
        } catch (Throwable t) {
            auditLogger.audit(AuditLogger.AuditEntry.of(
                    auditLog.value(), String.valueOf(targetId),
                    AuditLogger.AuditEntry.FAILURE,
                    elapsedMs(start), t.getClass().getSimpleName() + ": " + t.getMessage()));
            throw t;
        }
    }

    private long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * Resolves the target resource id for the audit entry: the annotation's
     * {@code target()} SpEL expression when present (parameters exposed as
     * variables, plus {@code #args}), otherwise the first argument.
     */
    private Object resolveTarget(ProceedingJoinPoint pjp, AuditLog auditLog) {
        Object[] args = pjp.getArgs();
        if (args.length == 0) {
            return null;
        }
        String expressionText = auditLog.target();
        if (expressionText == null || expressionText.isBlank()) {
            return args[0];
        }
        try {
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            ctx.setVariable("args", args);
            // Expose parameters by name when compiled with -parameters.
            String[] names = ((MethodSignature) pjp.getSignature()).getParameterNames();
            if (names != null) {
                for (int i = 0; i < names.length && i < args.length; i++) {
                    ctx.setVariable(names[i], args[i]);
                }
            }
            Expression expression = expressionCache.computeIfAbsent(
                    auditLog.value() + "|" + expressionText, parser::parseExpression);
            Object value = expression.getValue(ctx);
            return value != null ? value : args[0];
        } catch (RuntimeException e) {
            log.debug("AuditLog target SpEL evaluation failed, falling back to args[0]: {}", e.getMessage());
            return args[0];
        }
    }
}
