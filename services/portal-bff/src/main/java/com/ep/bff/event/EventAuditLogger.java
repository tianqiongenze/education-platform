package com.ep.bff.event;

import com.ep.web.common.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D-228: structured AUDIT log emitter for portal-bff event consumers.
 * <p>
 * Writes one single-line, machine-parseable JSON entry per consumed
 * security-relevant domain event to the SLF4J logger named {@code AUDIT} —
 * the canonical seam from {@code com.ep.web.common.audit.AuditLogger}
 * (constant {@link AuditLogger#AUDIT_LOGGER_NAME}, reused here so event-driven
 * and request-driven audit entries ship through the same logger/appender).
 * Ship as-is to ELK/Loki/SLS.
 * <p>
 * Deliberately NOT going through {@code AuditLogger.audit(AuditEntry)}: that
 * seam sources user/tenant from request-scoped MDC ({@code userId} /
 * {@link com.ep.web.common.tenant.TenantContext}), which does not exist on a
 * Pulsar listener thread. The fields that matter here (userId, reason,
 * occurredAt, orderId, ...) come from the event payload itself, so this helper
 * emits them directly instead of inventing MDC context. If the audit pipeline
 * later moves to a Pulsar {@code audit-events} topic, swap this class for a
 * producer — it is the single seam for event-side audit entries.
 * <p>
 * Never throws: a broken reason string (illegal control characters) is escaped,
 * and a null field degrades to JSON {@code null} — an audit gap must never take
 * down event consumption.
 */
final class EventAuditLogger {

    /** Single shared SLF4J logger named {@code AUDIT} (canonical seam). */
    private static final Logger audit = LoggerFactory.getLogger(AuditLogger.AUDIT_LOGGER_NAME);

    private EventAuditLogger() {
    }

    /**
     * {@code UserFrozenEvent} audit entry (risk-control P1-8 continuation):
     * {@code {"action":"USER_FROZEN","userId":…,"reason":"…","occurredAt":"…"}}.
     *
     * @param userId     frozen user (non-null when called by {@link UserFrozenConsumer})
     * @param reason     freeze reason from the aggregate, may be null
     * @param occurredAt ISO-8601 event timestamp from the payload, may be null
     */
    static void userFrozen(Long userId, String reason, String occurredAt) {
        audit.info("{\"action\":\"USER_FROZEN\",\"userId\":" + numberOr(userId)
                + ",\"reason\":" + quote(reason)
                + ",\"occurredAt\":" + quote(occurredAt) + "}");
    }

    /**
     * {@code OrderPaidEvent} audit entry:
     * {@code {"action":"ORDER_PAID","orderId":…,"userId":…,"courseId":…,
     * "productType":"…","occurredAt":"…"}}.
     *
     * @param orderId     paid order id (non-null when called by {@link OrderPaidConsumer})
     * @param userId      ordering user, may be null in the payload
     * @param courseId    product id, may be null for non-inventory products
     * @param productType product kind (COURSE, MEMBERSHIP, ...), may be null
     * @param occurredAt  ISO-8601 event timestamp from the payload, may be null
     */
    static void orderPaid(Long orderId, Long userId, Long courseId, String productType, String occurredAt) {
        audit.info("{\"action\":\"ORDER_PAID\",\"orderId\":" + numberOr(orderId)
                + ",\"userId\":" + numberOr(userId)
                + ",\"courseId\":" + numberOr(courseId)
                + ",\"productType\":" + quote(productType)
                + ",\"occurredAt\":" + quote(occurredAt) + "}");
    }

    /** JSON number or literal {@code null}. */
    private static String numberOr(Long value) {
        return value == null ? "null" : Long.toString(value);
    }

    /** Minimal JSON string escaping (quotes, backslash, control chars). */
    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
