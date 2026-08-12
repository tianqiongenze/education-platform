package com.ep.order.api;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Pure serializable view of an order, returned over the Dubbo RPC boundary.
 * <p>
 * NOT a JPA entity — the persistent {@code OrderModel} lives inside
 * {@code order-platform-service}. Keeping this a record (no JPA annotations)
 * means consumers (BFF, other platforms) never drag Hibernate onto their
 * classpath. D-040: record — GraalVM AOT friendly.
 *
 * @param orderId        primary key (ep_order.order_id)
 * @param userId         ordering user
 * @param courseId       product id (named courseId for back-compat; null for non-inventory products)
 * @param tenantId       D-044 multi-tenant scope
 * @param productType    product kind (COURSE, MEMBERSHIP, ...)
 * @param amount         money to charge
 * @param status         {@link OrderStatus#getCode()}
 * @param payMethod      free-text pay method resolved to a PaymentChannel
 * @param idempotencyKey unique idempotency key
 * @param createdAt      epoch millis
 * @param updatedAt      epoch millis
 */
public record OrderDto(
        Long orderId,
        Long userId,
        Long courseId,
        String tenantId,
        String productType,
        BigDecimal amount,
        Integer status,
        String payMethod,
        String idempotencyKey,
        Long createdAt,
        Long updatedAt
) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Default tenant id when none is supplied (single-tenant back-compat). */
    public static final String DEFAULT_TENANT = "default";
    /** Product type for course orders (the legacy/only kind before generalisation). */
    public static final String PRODUCT_COURSE = "COURSE";
}
