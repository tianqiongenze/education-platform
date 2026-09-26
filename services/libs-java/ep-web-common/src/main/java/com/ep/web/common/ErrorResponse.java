package com.ep.web.common;

/**
 * Error response body returned by the global exception handler for all
 * unhandled exceptions. The body is intentionally structured (no stack
 * traces) so BFF clients can parse it reliably.
 *
 * @param code      stable error code, e.g. "BAD_REQUEST", "FORBIDDEN",
 *                  "INTERNAL_ERROR"
 * @param message   human-readable message
 * @param traceId   MDC trace ID (or "N/A")
 * @param timestamp epoch millis
 */
public record ErrorResponse(
        String code,
        String message,
        String traceId,
        long timestamp
) {
}
