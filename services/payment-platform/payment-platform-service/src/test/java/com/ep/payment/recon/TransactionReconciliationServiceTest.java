package com.ep.payment.recon;

import com.ep.payment.api.PaymentStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * D-239/A4: 交易对账 engine tests — match / platform-only / channel-only /
 * amount-mismatch classification and the match posting.
 */
@ExtendWith(MockitoExtension.class)
class TransactionReconciliationServiceTest {

    @Mock
    private ChannelStatementRepository statementRepository;
    @Mock
    private com.ep.payment.repository.PaymentJpaRepository paymentRepository;
    @Mock
    private ReconDifferenceRepository differenceRepository;
    @Mock
    private com.ep.payment.ledger.LedgerService ledgerService;

    private static final Instant T = Instant.parse("2026-10-27T10:00:00Z");
    private static final String DATE = "2026-10-27";

    private TransactionReconciliationService service() {
        return new TransactionReconciliationService(statementRepository, paymentRepository,
                differenceRepository, ledgerService);
    }

    private com.ep.payment.entity.PaymentEntity payment(String id, String channelTxId, String amount) {
        com.ep.payment.entity.PaymentEntity p = new com.ep.payment.entity.PaymentEntity();
        p.setPaymentId(id);
        p.setChannelTxId(channelTxId);
        p.setAmount(new BigDecimal(amount));
        p.setStatus(PaymentStatus.SUCCESS);
        p.setPaidAt(T);
        p.setTenantId("default");
        return p;
    }

    private ChannelStatementEntity stmt(String lineId, String channelTxId, String paymentId, String amount) {
        return new ChannelStatementEntity(lineId, channelTxId, paymentId,
                new BigDecimal(amount), "SUCCESS", DATE, T);
    }

    private void stubHappy() {
        lenient().when(statementRepository.findByBizDate(DATE)).thenReturn(List.of());
        lenient().when(paymentRepository.findAll()).thenReturn(List.of());
        lenient().when(differenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(ledgerService.postChannelCleared(anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> null);
    }

    @Test
    @DisplayName("matched payments post 交易对账 voucher and produce no differences")
    void matchedPath() {
        stubHappy();
        when(statementRepository.findByBizDate(DATE)).thenReturn(List.of(
                stmt("L1", "CTX-1", "P1", "100.00")));
        when(paymentRepository.findAll()).thenReturn(List.of(
                payment("P1", "CTX-1", "100.00")));
        var result = service().reconcile(DATE);
        assertEquals(1, result.matched());
        assertEquals(0, result.totalDifferences());
        org.mockito.Mockito.verify(ledgerService).postChannelCleared(
                eq("default"), eq("RC-L1"), eq(new BigDecimal("100.00")), any());
    }

    @Test
    @DisplayName("platform-only payment → PLATFORM_ONLY difference (在途)")
    void platformOnly() {
        stubHappy();
        when(paymentRepository.findAll()).thenReturn(List.of(
                payment("P1", "CTX-MISSING", "100.00")));
        var result = service().reconcile(DATE);
        assertEquals(1, result.platformOnly());
        org.mockito.ArgumentCaptor<ReconDifferenceEntity> cap =
                org.mockito.ArgumentCaptor.forClass(ReconDifferenceEntity.class);
        org.mockito.Mockito.verify(differenceRepository).save(cap.capture());
        assertEquals(ReconDifferenceEntity.DiffKind.PLATFORM_ONLY, cap.getValue().getKind());
    }

    @Test
    @DisplayName("channel-only line → CHANNEL_ONLY difference (长款)")
    void channelOnly() {
        stubHappy();
        when(statementRepository.findByBizDate(DATE)).thenReturn(List.of(
                stmt("L1", "CTX-ORPHAN", null, "50.00")));
        var result = service().reconcile(DATE);
        assertEquals(1, result.channelOnly());
        org.mockito.ArgumentCaptor<ReconDifferenceEntity> cap =
                org.mockito.ArgumentCaptor.forClass(ReconDifferenceEntity.class);
        org.mockito.Mockito.verify(differenceRepository).save(cap.capture());
        assertEquals(ReconDifferenceEntity.DiffKind.CHANNEL_ONLY, cap.getValue().getKind());
    }

    @Test
    @DisplayName("amount mismatch → AMOUNT_MISMATCH difference")
    void amountMismatch() {
        stubHappy();
        when(statementRepository.findByBizDate(DATE)).thenReturn(List.of(
                stmt("L1", "CTX-1", "P1", "80.00")));
        when(paymentRepository.findAll()).thenReturn(List.of(
                payment("P1", "CTX-1", "100.00")));
        var result = service().reconcile(DATE);
        assertEquals(1, result.amountMismatch());
        org.mockito.ArgumentCaptor<ReconDifferenceEntity> cap =
                org.mockito.ArgumentCaptor.forClass(ReconDifferenceEntity.class);
        org.mockito.Mockito.verify(differenceRepository).save(cap.capture());
        assertEquals(ReconDifferenceEntity.DiffKind.AMOUNT_MISMATCH, cap.getValue().getKind());
    }

    @Test
    @DisplayName("resolve write-off posts the ledger write-off voucher")
    void resolveWriteOff() {
        stubHappy();
        ReconDifferenceEntity diff = new ReconDifferenceEntity("D1",
                ReconDifferenceEntity.DiffKind.CHANNEL_ONLY,
                ReconDifferenceEntity.ReconDimension.TRANSACTION,
                null, "CTX-X", new BigDecimal("50"), DATE,
                ReconDifferenceEntity.DiffStatus.OPEN, null);
        when(differenceRepository.findById("D1")).thenReturn(Optional.of(diff));
        when(differenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerService.postWriteOff(any(), any(), any(), eq(true), any()))
                .thenAnswer(inv -> null);
        String status = service().resolveDifference("D1",
                ReconDifferenceEntity.DiffStatus.RESOLVED_WRITE_OFF);
        assertEquals("RESOLVED_WRITE_OFF", status);
        org.mockito.Mockito.verify(ledgerService).postWriteOff(
                any(), eq("D1"), eq(new BigDecimal("50")), eq(true), any());
    }

    @Test
    @DisplayName("resolve unknown diff throws")
    void resolveUnknownThrows() {
        stubHappy();
        when(differenceRepository.findById("DX")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () ->
                service().resolveDifference("DX", ReconDifferenceEntity.DiffStatus.RESOLVED_IN_TRANSIT));
    }
}