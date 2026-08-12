package com.ep.payment.service;

import com.ep.payment.api.Payment;
import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentRequest;
import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentStatus;
import com.ep.payment.api.Refund;
import com.ep.payment.api.RefundStatus;
import com.ep.payment.channel.MockPaymentChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PaymentServiceImpl}. Uses the in-memory
 * {@link MockPaymentChannel} (EP_DEV_ONLY) — no Spring context, no real
 * gateway. Migrated + enhanced from {@code core-business}.
 */
class PaymentServiceImplTest {

    private PaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        // devGuard=true forces the MOCK channel even if ALIPAY/WECHAT beans
        // were registered — that is exactly the dev/CI contract.
        service = new PaymentServiceImpl(List.of(new MockPaymentChannel()), true);
    }

    private PaymentRequest request(String orderId, PaymentChannel channel) {
        return new PaymentRequest(orderId, "tenant-A", channel, new BigDecimal("100.00"), "CNY");
    }

    @Test
    void createPayment_success_mockChannel() {
        PaymentResult result = service.createPayment(request("order-1", PaymentChannel.MOCK));
        assertEquals(PaymentStatus.SUCCESS, result.status());
        assertNotNull(result.channelTxId());
        assertNotNull(result.paidAt());
        // Payment id is a UUID v4.
        assertNotNull(result.paymentId());
        assertTrue(result.paymentId().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "paymentId should be a UUID: " + result.paymentId());
    }

    @Test
    void createPayment_nullRequest_throws() {
        assertThrows(NullPointerException.class, () -> service.createPayment(null));
    }

    @Test
    void createPayment_blankOrderId_throws() {
        PaymentRequest req = new PaymentRequest("  ", "tenant-A", PaymentChannel.MOCK,
                new BigDecimal("100.00"), "CNY");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createPayment(req));
        assertTrue(ex.getMessage().contains("orderId"));
    }

    @Test
    void createPayment_zeroAmount_throws() {
        PaymentRequest req = new PaymentRequest("order-z", "tenant-A", PaymentChannel.MOCK,
                BigDecimal.ZERO, "CNY");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createPayment(req));
        assertTrue(ex.getMessage().contains("amount"));
    }

    @Test
    void createPayment_devGuard_forcesMockChannel() {
        // Even though ALIPAY is requested, devGuard=true must override to MOCK,
        // and there is no ALIPAY bean — so the effective channel is MOCK.
        PaymentResult result = service.createPayment(
                new PaymentRequest("order-dev", "tenant-A", PaymentChannel.ALIPAY,
                        new BigDecimal("100.00"), "CNY"));
        assertEquals(PaymentStatus.SUCCESS, result.status());
        Payment stored = service.getPayment(result.paymentId());
        assertEquals(PaymentChannel.MOCK, stored.getChannel(),
                "devGuard must force the stored channel to MOCK, not the requested ALIPAY");
    }

    @Test
    void getPayment_found() {
        PaymentResult result = service.createPayment(request("order-3", PaymentChannel.MOCK));
        Payment fetched = service.getPayment(result.paymentId());
        assertNotNull(fetched);
        assertEquals(result.paymentId(), fetched.getPaymentId());
        assertEquals("order-3", fetched.getOrderId());
        assertEquals("tenant-A", fetched.getTenantId());
        assertEquals(new BigDecimal("100.00"), fetched.getAmount());
    }

    @Test
    void getPayment_notFound() {
        assertNull(service.getPayment("does-not-exist"));
    }

    @Test
    void getPaymentByOrder_found() {
        PaymentResult result = service.createPayment(request("order-4", PaymentChannel.MOCK));
        Payment fetched = service.getPaymentByOrder("order-4");
        assertNotNull(fetched);
        assertEquals(result.paymentId(), fetched.getPaymentId());
    }

    @Test
    void refund_success_fullRefund_marksRefunded() {
        service.createPayment(request("order-5", PaymentChannel.MOCK));
        Refund refund = service.refund("order-5", new BigDecimal("100.00"), "user request");
        assertEquals(RefundStatus.SUCCESS, refund.getStatus());
        assertNotNull(refund.getRefundId());
        assertNotNull(refund.getRefundedAt());
        assertEquals("user request", refund.getReason());
        Payment payment = service.getPaymentByOrder("order-5");
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus(),
                "full refund must flip the Payment to REFUNDED");
    }

    @Test
    void refund_noPayment_throws() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.refund("no-such-order", new BigDecimal("10.00"), "x"));
        assertTrue(ex.getMessage().contains("No payment for order"));
    }

    @Test
    void refund_notRefundable_throws() {
        PaymentResult result = service.createPayment(request("order-6", PaymentChannel.MOCK));
        Payment payment = service.getPayment(result.paymentId());
        assertNotNull(payment);
        // Force a non-refundable state to exercise the guard.
        payment.setStatus(PaymentStatus.FAILED);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.refund("order-6", new BigDecimal("100.00"), "x"));
        assertTrue(ex.getMessage().contains("not refundable"));
    }

    @Test
    void refund_partialRefund_keepsPaymentSuccess() {
        service.createPayment(new PaymentRequest("order-p", "tenant-A", PaymentChannel.MOCK,
                new BigDecimal("100.00"), "CNY"));
        Refund refund = service.refund("order-p", new BigDecimal("30.00"), "partial");
        assertEquals(RefundStatus.SUCCESS, refund.getStatus());
        Payment payment = service.getPaymentByOrder("order-p");
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus(),
                "partial refund must keep the Payment SUCCESS so further refunds are possible");
    }

    @Test
    void getPaymentByOrder_blankOrderId_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.getPaymentByOrder(""));
    }

    @Test
    void refund_zeroAmount_throws() {
        service.createPayment(request("order-z2", PaymentChannel.MOCK));
        assertThrows(IllegalArgumentException.class,
                () -> service.refund("order-z2", BigDecimal.ZERO, "x"));
    }

    @Test
    void createPayment_unknownChannel_fallsBackToMock_whenDevGuardOff() {
        // devGuard=false so the requested ALIPAY channel is honoured — but with
        // no ALIPAY SPI registered it must fall back to MOCK (default channel).
        PaymentServiceImpl noGuard = new PaymentServiceImpl(List.of(new MockPaymentChannel()), false);
        PaymentResult result = noGuard.createPayment(
                new PaymentRequest("order-fb", "tenant-A", PaymentChannel.ALIPAY,
                        new BigDecimal("100.00"), "CNY"));
        assertEquals(PaymentStatus.SUCCESS, result.status(),
                "unknown channel must fall back to MOCK which succeeds");
        Payment stored = noGuard.getPayment(result.paymentId());
        assertEquals(PaymentChannel.MOCK, stored.getChannel(),
                "stored channel must be the resolved (MOCK) channel, not the requested ALIPAY");
    }

    @Test
    void createPayment_synthesisesMockChannel_whenNoSpiBeansProvided() {
        // No SPI beans at all — the service must still work by synthesising a
        // MockPaymentChannel internally.
        PaymentServiceImpl empty = new PaymentServiceImpl(List.of(), false);
        PaymentResult result = assertDoesNotThrow(
                () -> empty.createPayment(request("order-empty", PaymentChannel.MOCK)));
        assertEquals(PaymentStatus.SUCCESS, result.status());
    }

    @Test
    void refund_recordsTenantAndOrderDenormalised() {
        service.createPayment(request("order-td", PaymentChannel.MOCK));
        Refund refund = service.refund("order-td", new BigDecimal("100.00"), "x");
        assertEquals("order-td", refund.getOrderId());
        assertEquals("tenant-A", refund.getTenantId());
    }

    @Test
    void createPayment_sameOrderIdDifferentTenants_distinctPayments() {
        PaymentRequest reqT1 = new PaymentRequest("order-dup", "tenant-A", PaymentChannel.MOCK,
                new BigDecimal("50.00"), "CNY");
        PaymentRequest reqT2 = new PaymentRequest("order-dup", "tenant-B", PaymentChannel.MOCK,
                new BigDecimal("50.00"), "CNY");
        PaymentResult r1 = service.createPayment(reqT1);
        PaymentResult r2 = service.createPayment(reqT2);
        assertFalse(r1.paymentId().equals(r2.paymentId()),
                "two payments for the same order id must have distinct payment ids");
        Payment p1 = service.getPayment(r1.paymentId());
        Payment p2 = service.getPayment(r2.paymentId());
        assertEquals("tenant-A", p1.getTenantId());
        assertEquals("tenant-B", p2.getTenantId());
    }

    @Test
    void createPayment_nullChannel_throws() {
        PaymentRequest req = new PaymentRequest("order-nc", "tenant-A", null,
                new BigDecimal("100.00"), "CNY");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createPayment(req));
        assertTrue(ex.getMessage().contains("channel"));
    }
}
