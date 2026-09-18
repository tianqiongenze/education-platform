package com.ep.web.common.audit;

import com.ep.web.common.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;

/**
 * Risk-control P1-8: writes one structured JSON audit entry per security-
 * relevant action to the SLF4J logger named {@code AUDIT}.
 *
 * <p>Entry shape (single line, machine-parseable — ship as-is to
 * ELK/Loki/SLS):</p>
 * <pre>
 * {"ts":"...","user":"123","tenant":"default","action":"ORDER_REFUND",
 *  "target":"5001","result":"SUCCESS","durationMs":12,"traceId":"...","error":null}
 * </pre>
 *
 * <p>Identity sources: user from the MDC key {@code userId} (populated by the
 * BFF/edge from the trusted {@code X-User-Id} header when wired), tenant from
 * {@link TenantContext}, trace id from the MDC key {@code traceId}.</p>
 *
 * <p>Future: the same JSON payload can be published to a Pulsar
 * {@code audit-events} topic for durable, tamper-evident storage — swap the
 * SLF4J appender for a Pulsar producer without touching call sites (the logger
 * is the single seam).</p>
 */
public class AuditLogger {

    /** Canonical logger name for audit entries (see logback config per service). */
    public static final String AUDIT_LOGGER_NAME = "AUDIT";

    private static final Logger log = LoggerFactory.getLogger(AUDIT_LOGGER_NAME);

    /**
     * Builds the JSON entry. Package-private seam: {@link AuditLogAspect} and
     * tests call this; production code goes through {@link #audit(AuditEntry)}.
     */
    static String toJson(AuditEntry entry) {
        return "{\"ts\":" + quote(entry.timestamp())
                + ",\"user\":" + quote(entry.user())
                + ",\"tenant\":" + quote(entry.tenant())
                + ",\"action\":" + quote(entry.action())
                + ",\"target\":" + quote(entry.target())
                + ",\"result\":" + quote(entry.result())
                + ",\"durationMs\":" + entry.durationMs()
                + ",\"traceId\":" + quote(entry.traceId())
                + ",\"error\":" + quote(entry.error())
                + "}";
    }

    /** Emits the entry to the {@code AUDIT} logger at INFO level. */
    public void audit(AuditEntry entry) {
        if (entry == null) {
            return;
        }
        log.info(toJson(entry));
    }

    /** Minimal JSON string escaping (quotes, backslash, control chars). */
    static String quote(String value) {
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

    /** Immutable audit record. */
    public record AuditEntry(String timestamp, String user, String tenant, String action,
                             String target, String result, long durationMs,
                             String traceId, String error) {

        public static final String SUCCESS = "SUCCESS";
        public static final String FAILURE = "FAILURE";

        public static AuditEntry of(String action, String target, String result,
                                    long durationMs, String error) {
            String user = MDC.get("userId");
            String tenant = TenantContext.getTenantId();
            if (tenant == null || tenant.isBlank()) {
                tenant = TenantContext.DEFAULT_TENANT_ID;
            }
            return new AuditEntry(Instant.now().toString(),
                    user == null ? "unknown" : user,
                    tenant, action,
                    target == null ? "" : target,
                    result, Math.max(0, durationMs),
                    MDC.get("traceId"), error);
        }
    }
}
