package com.ep.order.api;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * D-222: API view of an open/failed payment Saga refund compensation.
 * <p>
 * Entities never cross the controller boundary (same convention as
 * {@code OrderDto}); instants are rendered as ISO-8601 strings so the JSON
 * contract stays timezone-explicit.
 */
public record PaymentCompensationView(
        String failureId,
        String orderId,
        String tenantId,
        BigDecimal amount,
        String reason,
        String failedAt,
        boolean resolved,
        String resolvedNote,
        String resolvedAt) implements Serializable {
}
