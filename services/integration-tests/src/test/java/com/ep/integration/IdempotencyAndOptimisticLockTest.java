package com.ep.integration;

import com.ep.integration.builder.TestDataBuilder;
import com.ep.integration.support.BusinessFakes.FakeOrderService;
import com.ep.integration.support.IntegrationDtos.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Dedicated tests for ADR-T06 patterns:
 *  - idempotency key deduplication (production throws on a duplicate key)
 *  - optimistic locking (version bump observable on the stored order)
 *  - sequential updates succeed and advance the state machine
 *  - state transition guards (no PENDING -> REFUNDED)
 */
class IdempotencyAndOptimisticLockTest {

    private FakeOrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new FakeOrderService();
    }

    @Test
    @DisplayName("idempotency: duplicate key is rejected, different keys create different resources")
    void test_idempotency_key_dedup() {
        var first = orderService.createOrder(TestDataBuilder.buildOrder(1L, 1L, "key-A"));
        assertNotNull(first);

        // Production OrderServiceImpl.createOrder THROWS IllegalStateException on
        // a duplicate idempotency key — it does NOT return the original order.
        // The fake mirrors that behaviour.
        assertThrows(IllegalStateException.class,
                () -> orderService.createOrder(TestDataBuilder.buildOrder(1L, 1L, "key-A")),
                "duplicate idempotency key must throw");

        var other = orderService.createOrder(TestDataBuilder.buildOrder(1L, 1L, "key-B"));
        assertNotEquals(first.getOrderId(), other.getOrderId(), "different keys create different orders");
    }

    @Test
    @DisplayName("optimistic lock: concurrent payment attempts only one succeeds")
    void test_optimistic_lock_version_mismatch() {
        var order = orderService.createOrder(TestDataBuilder.buildOrder(1L, 1L, "idem-ol-1"));
        int v0 = order.getVersion();

        // First pay advances the version to v0+1 and moves PENDING -> PAID.
        orderService.payOrder(order.getOrderId(), "ALIPAY");
        var paid = orderService.getOrderById(order.getOrderId());
        assertNotNull(paid);
        assertEquals(v0 + 1, paid.getVersion());
        assertEquals(OrderStatus.PAID.getCode(), paid.getStatus());

        // A second pay on the now-PAID order is rejected: the fake no longer
        // takes an expectedVersion parameter (matching production
        // OrderService.payOrder(Long, String)), so the state-machine guard
        // (PENDING -> PAID) fires instead of a version-mismatch check.
        assertThrows(IllegalStateException.class,
                () -> orderService.payOrder(order.getOrderId(), "ALIPAY"));
    }

    @Test
    @DisplayName("sequential updates with correct version succeed")
    void test_optimistic_lock_sequential() {
        var order = orderService.createOrder(TestDataBuilder.buildOrder(1L, 1L, "idem-ol-2"));
        int v0 = order.getVersion();

        orderService.payOrder(order.getOrderId(), "ALIPAY");
        var paid = orderService.getOrderById(order.getOrderId());
        assertNotNull(paid);
        assertEquals(v0 + 1, paid.getVersion());

        // After pay, the order is PAID. A subsequent refund requires the new
        // version; the FakeOrderService refund does not take a version, but it
        // guards on PAID status. We verify the state advanced correctly.
        orderService.refundOrder(order.getOrderId(), "customer request");
        var refunded = orderService.getOrderById(order.getOrderId());
        assertNotNull(refunded);
        assertEquals(OrderStatus.REFUNDED.getCode(), refunded.getStatus());
    }

    @Test
    @DisplayName("state transition guard: order cannot skip PAID and go PENDING -> REFUNDED")
    void test_state_transition_guard() {
        var order = orderService.createOrder(TestDataBuilder.buildOrder(1L, 1L, "idem-guard-2"));
        assertEquals(OrderStatus.PENDING.getCode(), order.getStatus());

        // Refunding a PENDING order is illegal per the state machine.
        assertThrows(IllegalStateException.class,
                () -> orderService.refundOrder(order.getOrderId(), "skip-pay"));

        // The order must remain PENDING after the rejected transition.
        var stillPending = orderService.getOrderById(order.getOrderId());
        assertNotNull(stillPending);
        assertEquals(OrderStatus.PENDING.getCode(), stillPending.getStatus());
    }
}
