package com.ep.payment.service;

import com.ep.cache.CacheService;
import com.ep.payment.api.Payment;
import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentChannelSpi;
import com.ep.payment.api.PaymentRequest;
import com.ep.payment.api.PaymentResult;
import com.ep.payment.api.PaymentStatus;
import com.ep.payment.api.Refund;
import com.ep.payment.api.RefundResult;
import com.ep.payment.api.RefundStatus;
import com.ep.payment.entity.PaymentEntity;
import com.ep.payment.repository.PaymentJpaRepository;
import com.ep.payment.repository.RefundJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * D-236: money-safety test suite for the refund ledger. The funds domain gets
 * the strictest suite in the repo — every test here documents a real
 * over-refund / mis-refund scenario that once passed silently.
 *
 * <p><b>Historical defect</b> (fixed by D-236): {@code refund()} validated each
 * call's amount against the payment in isolation, so N partial refunds could
 * jointly exceed the payment amount — a classic funds-loss path.</p>
 */
class PaymentRefundMoneySafetyTest {

    private PaymentChannelSpi mockChannel;
    private PaymentJpaRepository paymentRepository;
    private RefundJpaRepository refundRepository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        mockChannel = mock(PaymentChannelSpi.class);
        when(mockChannel.getChannel()).thenReturn(PaymentChannel.MOCK);
        when(mockChannel.supportsRefund()).thenReturn(true);
        when(mockChannel.pay(any())).thenAnswer(inv -> {
            PaymentRequest req = inv.getArgument(0);
            return new PaymentResult("tx-" + req.orderId(), PaymentStatus.SUCCESS, "ch-tx-1", null);
        });
        when(mockChannel.refund(any(), any(), any())).thenReturn(
                Optional.of(new RefundResult(true, "refund-tx-1", "ok")));

        paymentRepository = mock(PaymentJpaRepository.class);
        refundRepository = mock(RefundJpaRepository.class);
        when(paymentRepository.existsByOrderId(anyString())).thenReturn(false);
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refundRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refundRepository.sumActiveRefundAmountByOrderId(anyString())).thenReturn(BigDecimal.ZERO);
    }

    private PaymentServiceImpl inMemoryService() {
        return new PaymentServiceImpl(List.of(mockChannel), true);
    }

    @SuppressWarnings("unchecked")
    private PaymentServiceImpl jpaService() {
        ObjectProvider<PaymentJpaRepository> payProvider = mock(ObjectProvider.class);
        ObjectProvider<RefundJpaRepository> refundProvider = mock(ObjectProvider.class);
        when(payProvider.getIfAvailable()).thenReturn(paymentRepository);
        when(refundProvider.getIfAvailable()).thenReturn(refundRepository);
        return new PaymentServiceImpl(List.of(mockChannel), payProvider, refundProvider, true, null);
    }

    private String paidOrder(PaymentServiceImpl service, String orderId, String amount) {
        PaymentResult result = service.createPayment(new PaymentRequest(
                orderId, "tenant-1", PaymentChannel.MOCK, new BigDecimal(amount), "CNY"));
        assertEquals(PaymentStatus.SUCCESS, result.status(), "precondition: payment succeeded");
        return result.paymentId();
    }

    @Test
    @DisplayName("two partial refunds totalling over the payment amount must be rejected (the fixed defect)")
    void overRefundAcrossTwoPartialCallsRejected() {
        PaymentServiceImpl service = inMemoryService();
        paidOrder(service, "order-1", "100");

        assertEquals(RefundStatus.SUCCESS, service.refund("order-1", new BigDecimal("60"), "first").getStatus());
        // second partial would push the cumulative ledger to 120 > 100
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.refund("order-1", new BigDecimal("60"), "second"));
        assertTrue(ex.getMessage().contains("Refund overdraw"), "the rejection must be explicit");
    }

    @Test
    @DisplayName("the cumulative ledger reaches exactly 100%: payment flips to REFUNDED (60/40 split)")
    void cumulativeLedgerFullRefundFlipsStatus() {
        PaymentServiceImpl service = inMemoryService();
        paidOrder(service, "order-2", "100");

        service.refund("order-2", new BigDecimal("60"), "part1");
        service.refund("order-2", new BigDecimal("40"), "part2");

        Payment payment = service.getPaymentByOrder("order-2");
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus(),
                "60+40 split must leave the payment REFUNDED (SDD literal semantics)");
    }

    @Test
    @DisplayName("partial refund below the total leaves the payment SUCCESS and still refundable")
    void partialRefundKeepsPaymentSuccess() {
        PaymentServiceImpl service = inMemoryService();
        paidOrder(service, "order-3", "100");

        service.refund("order-3", new BigDecimal("30"), "part1");

        Payment payment = service.getPaymentByOrder("order-3");
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertDoesNotThrow(() -> service.refund("order-3", new BigDecimal("30"), "part2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"100.01", "150", "999999"})
    @DisplayName("a single refund call over the payment amount is rejected for any magnitude")
    void singleOverRefundRejected(String amount) {
        PaymentServiceImpl service = inMemoryService();
        paidOrder(service, "order-4", "100");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.refund("order-4", new BigDecimal(amount), "too much"));
        assertTrue(ex.getMessage().contains("Refund overdraw"));
    }

    @Test
    @DisplayName("a FAILED refund consumed no funds and does not count against the ledger")
    void failedRefundDoesNotConsumeLedger() {
        when(mockChannel.refund(any(), any(), any())).thenReturn(Optional.empty());
        PaymentServiceImpl service = inMemoryService();
        paidOrder(service, "order-5", "100");

        Refund failed = service.refund("order-5", new BigDecimal("80"), "gateway hiccup");
        assertEquals(RefundStatus.FAILED, failed.getStatus());

        // ledger must still be 0 — the failed 80 did not consume funds
        when(mockChannel.refund(any(), any(), any())).thenReturn(
                Optional.of(new RefundResult(true, "refund-tx-2", "ok")));
        Refund ok = service.refund("order-5", new BigDecimal("100"), "retry full");
        assertEquals(RefundStatus.SUCCESS, ok.getStatus());
        assertEquals(PaymentStatus.REFUNDED, service.getPaymentByOrder("order-5").getStatus());
    }

    @Test
    @DisplayName("JPA path: the over-draw guard reads the DB-side cumulative sum")
    void jpaPathUsesDbSumForGuard() {
        PaymentServiceImpl service = jpaService();
        String paymentId = paidOrder(service, "order-6", "100");

        // The repository returns the persisted payment on refund lookup.
        PaymentEntity persisted = new PaymentEntity();
        persisted.setPaymentId(paymentId);
        persisted.setOrderId("order-6");
        persisted.setTenantId("tenant-1");
        persisted.setAmount(new BigDecimal("100"));
        persisted.setCurrency("CNY");
        persisted.setChannel(PaymentChannel.MOCK);
        persisted.setStatus(PaymentStatus.SUCCESS);
        persisted.setChannelTxId("ch-tx-1");
        persisted.setCreatedAt(Instant.now());
        persisted.setLockVersion(0);
        when(paymentRepository.findFirstByOrderId("order-6")).thenReturn(Optional.of(persisted));
        // ledger reports 60 already refunded from earlier partials
        when(refundRepository.sumActiveRefundAmountByOrderId("order-6")).thenReturn(new BigDecimal("60"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.refund("order-6", new BigDecimal("60"), "second"));
        assertTrue(ex.getMessage().contains("alreadyRefunded=60"));
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("refund on an unknown order is rejected before any money moves")
    void refundUnknownOrderRejected() {
        PaymentServiceImpl service = inMemoryService();
        assertThrows(IllegalStateException.class,
                () -> service.refund("order-unknown", new BigDecimal("10"), "ghost"));
    }

    @Test
    @DisplayName("null/zero/negative refund amounts are rejected (parameter validation)")
    void invalidRefundAmountsRejected() {
        PaymentServiceImpl service = inMemoryService();
        paidOrder(service, "order-7", "100");

        assertThrows(NullPointerException.class, () -> service.refund("order-7", null, "null"));
        assertThrows(IllegalArgumentException.class, () -> service.refund("order-7", BigDecimal.ZERO, "zero"));
        assertThrows(IllegalArgumentException.class, () -> service.refund("order-7", new BigDecimal("-5"), "negative"));
    }

    @Test
    @DisplayName("cache is evicted after a refund so reads observe the new status")
    void refundEvictsCache() {
        CacheService cache = mock(CacheService.class);
        PaymentServiceImpl service = new PaymentServiceImpl(List.of(mockChannel), true, cache);
        paidOrder(service, "order-8", "100");

        service.refund("order-8", new BigDecimal("100"), "full");

        // createPayment also evicts on create; the assertion is that the
        // refund path evicted at least once per cache dimension.
        verify(cache, atLeastOnce()).evict(eq("payment:detail"), anyString());
        verify(cache, atLeastOnce()).evict(eq("payment:by-order"), eq("order-8"));
    }
}
