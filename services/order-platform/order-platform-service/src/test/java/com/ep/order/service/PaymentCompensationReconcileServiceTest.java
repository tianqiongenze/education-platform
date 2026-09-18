package com.ep.order.service;

import com.ep.order.model.PaymentCompensationFailure;
import com.ep.order.repository.PaymentCompensationFailureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-222: unit tests for the payment compensation reconciliation service
 * (mocked repository — no Spring context, offline-safe).
 */
class PaymentCompensationReconcileServiceTest {

    private PaymentCompensationFailureRepository repository;
    private PaymentCompensationReconcileService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentCompensationFailureRepository.class);
        service = new PaymentCompensationReconcileService(repository);
    }

    @Test
    @DisplayName("recordFailure persists an open row with a UUID id and truncated reason")
    void recordFailurePersistsOpenRow() {
        when(repository.save(any(PaymentCompensationFailure.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        String longReason = "x".repeat(600);
        PaymentCompensationFailure failure = service.recordFailure(
                "order-1", "tenant-a", new BigDecimal("10.00"), longReason);

        assertFalse(failure.isResolved());
        assertEquals(36, failure.getFailureId().length());
        assertEquals(512, failure.getReason().length());
        assertEquals("order-1", failure.getOrderId());
        assertEquals("tenant-a", failure.getTenantId());
    }

    @Test
    @DisplayName("recordFailure tolerates null reason")
    void recordFailureNullReason() {
        when(repository.save(any(PaymentCompensationFailure.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PaymentCompensationFailure failure = service.recordFailure(
                "order-2", null, BigDecimal.ONE, null);

        assertEquals("unknown", failure.getReason());
        assertEquals(null, failure.getTenantId());
    }

    @Test
    @DisplayName("listUnresolved delegates to the repository open-failures query")
    void listUnresolvedDelegates() {
        PaymentCompensationFailure open = PaymentCompensationFailure.open(
                "f-1", "order-1", "t", BigDecimal.TEN, "boom", Instant.now());
        when(repository.findByResolvedFalseOrderByFailedAtDesc()).thenReturn(List.of(open));

        List<PaymentCompensationFailure> result = service.listUnresolved();

        assertEquals(1, result.size());
        assertEquals("f-1", result.get(0).getFailureId());
        assertFalse(result.get(0).isResolved());
    }

    @Test
    @DisplayName("resolve closes an open row with note and timestamp")
    void resolveClosesOpenRow() {
        PaymentCompensationFailure open = PaymentCompensationFailure.open(
                "f-2", "order-1", "t", BigDecimal.TEN, "boom", Instant.now());
        when(repository.findById("f-2")).thenReturn(Optional.of(open));

        PaymentCompensationFailure resolved = service.resolve("f-2", "refunded in gateway console");

        assertTrue(resolved.isResolved());
        assertEquals("refunded in gateway console", resolved.getResolvedNote());
        assertTrue(resolved.getResolvedAt() != null);
    }

    @Test
    @DisplayName("resolve with unknown id throws IllegalArgumentException")
    void resolveUnknownIdThrows() {
        when(repository.findById("nope")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.resolve("nope", "note"));
    }

    @Test
    @DisplayName("resolve on an already-resolved row throws IllegalStateException")
    void resolveAlreadyResolvedThrows() {
        PaymentCompensationFailure closed = PaymentCompensationFailure.open(
                "f-3", "order-1", "t", BigDecimal.TEN, "boom", Instant.now());
        closed.resolve("first", Instant.now());
        when(repository.findById("f-3")).thenReturn(Optional.of(closed));

        assertThrows(IllegalStateException.class, () -> service.resolve("f-3", "second"));
    }

    @Test
    @DisplayName("resolve truncates long operator notes to the column limit")
    void resolveTruncatesNote() {
        PaymentCompensationFailure open = PaymentCompensationFailure.open(
                "f-4", "order-1", "t", BigDecimal.TEN, "boom", Instant.now());
        when(repository.findById("f-4")).thenReturn(Optional.of(open));

        PaymentCompensationFailure resolved = service.resolve("f-4", "y".repeat(600));

        assertEquals(512, resolved.getResolvedNote().length());
    }
}
