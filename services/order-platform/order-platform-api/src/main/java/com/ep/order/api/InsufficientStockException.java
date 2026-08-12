package com.ep.order.api;

import java.io.Serializable;

/**
 * Thrown by the simulated inventory branch of
 * {@link OrderService#createOrderWithGlobalTx(CreateOrderCmd, int)} when the
 * requested stock units exceed the available stock. Because the caller runs
 * inside a {@code @GlobalTransactional}, this exception triggers a Seata global
 * rollback that undoes the order insert (branch 1) via its AT undo log.
 * <p>
 * Implements {@link Serializable} so it can propagate across the Dubbo RPC
 * boundary to the caller.
 */
public class InsufficientStockException extends RuntimeException implements Serializable {

    private static final long serialVersionUID = 1L;

    private final long courseId;
    private final int requested;
    private final int available;

    public InsufficientStockException(Long courseId, int requested, int available) {
        super("Insufficient stock for course " + courseId + ": requested=" + requested + ", available=" + available);
        this.courseId = courseId;
        this.requested = requested;
        this.available = available;
    }

    public long getCourseId() {
        return courseId;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }
}
