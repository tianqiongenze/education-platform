package com.ep.order.saga;

import com.ep.order.service.PaymentCompensationReconcileService;
import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentRequest;
import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentService;
import com.ep.payment.api.PaymentStatus;
import com.ep.payment.api.Refund;
import com.ep.payment.api.RefundStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-222: unit tests for the Payment Saga compensable action, focusing on the
 * failed-refund path now persisting a durable reconciliation record instead of
 * silently swallowing the money gap.
 */
class PaymentCompensableActionTest {

    private PaymentService paymentService;
    private PaymentCompensationReconcileService reconcileService;
    private PaymentCompensableAction action;

    @BeforeEach
    void setUp() throws Exception {
        paymentService = mock(PaymentService.class);
        reconcileService = mock(PaymentCompensationReconcileService.class);
        action = new PaymentCompensableAction(reconcileService);
        Field field = PaymentCompensableAction.class.getDeclaredField("paymentService");
        field.setAccessible(true);
        field.set(action, paymentService);
    }

    private Map<String, Object> ctx() {
        Map<String, Object> context = new HashMap<>();
        context.put("orderId", "order-d222");
        context.put("amount", new BigDecimal("88.00"));
        context.put("tenantId", "tenant-a");
        return context;
    }

    @Test
    @DisplayName("createPayment success returns payment coordinates")
    void createPaymentSuccess() {
        PaymentResult result = new PaymentResult("pay-1", PaymentStatus.SUCCESS, "tx-1", Instant.now());
        when(paymentService.createPayment(any(PaymentRequest.class))).thenReturn(result);

        Map<String, Object> out = action.createPayment(ctx());

        assertEquals("pay-1", out.get("paymentId"));
        assertEquals("SUCCESS", out.get("status"));
        assertEquals("tx-1", out.get("channelTxId"));
    }

    @Test
    @DisplayName("createPayment failure propagates so the Saga triggers compensation")
    void createPaymentFailurePropagates() {
        when(paymentService.createPayment(any(PaymentRequest.class)))
                .thenThrow(new IllegalStateException("gateway down"));

        assertThrows(IllegalStateException.class, () -> action.createPayment(ctx()));
    }

    @Test
    @DisplayName("compensation success keeps best-effort contract and reports compensationFailed=false")
    void compensateSuccess() {
        Refund refund = new Refund("refund-1", "pay-1", "order-d222", "tenant-a",
                new BigDecimal("88.00"), RefundStatus.SUCCESS, "D-113 saga compensation",
                Instant.now());
        when(paymentService.refund(eq("order-d222"), any(BigDecimal.class), anyString()))
                .thenReturn(refund);

        Map<String, Object> out = action.compensateCreatePayment(ctx());

        assertEquals("refund-1", out.get("refundId"));
        assertEquals("SUCCESS", out.get("status"));
        assertEquals(false, out.get("compensationFailed"));
        verify(reconcileService, never()).recordFailure(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("D-222: compensation failure persists an open reconciliation record and does not propagate")
    void compensateFailureRecordsDurableGap() {
        when(paymentService.refund(eq("order-d222"), any(BigDecimal.class), anyString()))
                .thenThrow(new IllegalStateException("gateway timeout"));
        com.ep.order.model.PaymentCompensationFailure failure =
                com.ep.order.model.PaymentCompensationFailure.open(
                        "f-1", "order-d222", "tenant-a", new BigDecimal("88.00"),
                        "IllegalStateException: gateway timeout", java.time.Instant.now());
        when(reconcileService.recordFailure(eq("order-d222"), eq("tenant-a"),
                eq(new BigDecimal("88.00")), anyString())).thenReturn(failure);

        Map<String, Object> out = action.compensateCreatePayment(ctx());

        // best-effort: no exception propagated, LIFO chain continues
        assertEquals("REFUND_FAILED_REQUIRES_MANUAL_INTERVENTION", out.get("status"));
        assertEquals(Boolean.TRUE, out.get("compensationFailed"));
        assertEquals("f-1", out.get("failureId"));
        verify(reconcileService).recordFailure(eq("order-d222"), eq("tenant-a"),
                eq(new BigDecimal("88.00")), anyString());
    }

    @Test
    @DisplayName("D-222: failure-record persistence outage still does not propagate, empty failureId")
    void compensateFailureWithRecordPersistenceOutage() {
        when(paymentService.refund(eq("order-d222"), any(BigDecimal.class), anyString()))
                .thenThrow(new IllegalStateException("gateway timeout"));
        doThrow(new IllegalStateException("db down"))
                .when(reconcileService)
                .recordFailure(anyString(), anyString(), any(), anyString());

        Map<String, Object> out = action.compensateCreatePayment(ctx());

        assertEquals("REFUND_FAILED_REQUIRES_MANUAL_INTERVENTION", out.get("status"));
        assertEquals(Boolean.TRUE, out.get("compensationFailed"));
        assertEquals("", out.get("failureId"));
    }

    @Test
    @DisplayName("resolvePaymentChannel maps legacy aliases and defaults to MOCK")
    void resolvePaymentChannelMapping() {
        assertEquals(PaymentChannel.ALIPAY, PaymentCompensableAction.resolvePaymentChannel("zfb"));
        assertEquals(PaymentChannel.WECHAT, PaymentCompensableAction.resolvePaymentChannel("wechat"));
        assertEquals(PaymentChannel.MOCK, PaymentCompensableAction.resolvePaymentChannel(null));
        assertEquals(PaymentChannel.MOCK, PaymentCompensableAction.resolvePaymentChannel("unknown"));
        assertTrue(PaymentChannel.MOCK.name().equalsIgnoreCase("mock"));
        assertFalse(PaymentChannel.values().length == 0);
    }
}
