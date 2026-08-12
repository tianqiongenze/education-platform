package com.ep.order.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Command to create an order, sent over the Dubbo RPC boundary.
 * <p>
 * D-040: record — GraalVM AOT friendly.
 *
 * @param userId         ordering user (required)
 * @param courseId       product id (required for COURSE orders; null for non-inventory products)
 * @param amount         money to charge (required, &gt; 0)
 * @param idempotencyKey unique idempotency key (required, non-blank)
 * @param tenantId       D-044 tenant scope (optional; defaults to {@link OrderDto#DEFAULT_TENANT})
 * @param productType    product kind (optional; defaults to {@link OrderDto#PRODUCT_COURSE})
 */
public record CreateOrderCmd(
        @NotNull Long userId,
        Long courseId,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String idempotencyKey,
        String tenantId,
        String productType
) implements Serializable {

    private static final long serialVersionUID = 1L;
}
