package com.ep.order.model;

import com.ep.order.api.OrderStatus;
import com.ep.order.event.OrderPaidEvent;
import com.ep.order.event.OrderRefundedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the {@link OrderModel} aggregate root: state machine
 * (pay/refund transitions) and domain-event pull/clear semantics. No Spring
 * context, no DB - fast and isolated.
 */
class OrderModelTest {

    private static OrderModel pendingOrder() {
        OrderModel o = new OrderModel();
        o.setOrderId(1L);
        o.setUserId(100L);
        o.setCourseId(200L);
        o.setAmount(new BigDecimal("99.00"));
        o.setStatus(OrderStatus.PENDING.getCode());
        o.setIdempotencyKey("idem-1");
        o.setCreatedAt(1L);
        o.setUpdatedAt(1L);
        return o;
    }

    @Test
    void pay_transitionsPendingToPaid() {
        OrderModel o = pendingOrder();
        long before = o.getUpdatedAt();

        o.pay("WECHAT");

        assertEquals(OrderStatus.PAID.getCode(), o.getStatus());
        assertEquals("WECHAT", o.getPayMethod());
        assertTrue(o.getUpdatedAt() >= before, "updatedAt should advance on pay");
        // a single OrderPaidEvent must have been recorded
        List<?> events = o.pullEvents();
        assertEquals(1, events.size());
        assertInstanceOf(OrderPaidEvent.class, events.get(0));
    }

    @Test
    void pay_wrongStatus_throws() {
        OrderModel o = pendingOrder();
        o.setStatus(OrderStatus.PAID.getCode()); // already paid

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> o.pay("ALIPAY"));
        assertTrue(ex.getMessage().contains("PENDING"));
        // no event recorded when the transition is rejected
        assertTrue(o.pullEvents().isEmpty());
    }

    @Test
    void refund_transitionsPaidToRefunded() {
        OrderModel o = pendingOrder();
        o.pay("ALIPAY"); // PAID
        long before = o.getUpdatedAt();

        o.refund();

        assertEquals(OrderStatus.REFUNDED.getCode(), o.getStatus());
        assertTrue(o.getUpdatedAt() >= before, "updatedAt should advance on refund");
        // pay produced OrderPaidEvent, refund produced OrderRefundedEvent
        List<?> events = o.pullEvents();
        assertEquals(2, events.size());
        assertInstanceOf(OrderRefundedEvent.class, events.get(1));
    }

    @Test
    void refund_wrongStatus_throws() {
        OrderModel o = pendingOrder(); // PENDING, never paid

        IllegalStateException ex = assertThrows(IllegalStateException.class, o::refund);
        assertTrue(ex.getMessage().contains("PAID"));
    }

    @Test
    void pullEvents_returnsAndClearsEvents() {
        OrderModel o = pendingOrder();
        o.pay("WECHAT");
        // first pull returns the recorded events
        List<?> first = o.pullEvents();
        assertEquals(1, first.size());
        // second pull is empty - events were cleared
        List<?> second = o.pullEvents();
        assertNotNull(second);
        assertTrue(second.isEmpty());
        // pulling again is safe
        assertDoesNotThrow(o::pullEvents);
    }
}
