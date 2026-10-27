package com.ep.payment.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * D-238/A3: ledger engine tests — double-entry invariant, the 7 processing
 * step templates, and detail-to-aggregate folding.
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private LedgerAccountRepository accountRepository;
    @Mock
    private LedgerVoucherRepository voucherRepository;
    @Mock
    private LedgerEntryRepository entryRepository;

    private LedgerService service() {
        return new LedgerService(accountRepository, voucherRepository, entryRepository);
    }

    private void stubAccountLookups() {
        lenient().when(accountRepository.findByTenantIdAndSubject(anyString(), any()))
                .thenAnswer(inv -> Optional.empty());
        lenient().when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(voucherRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(entryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static final Instant T = Instant.parse("2026-10-27T00:00:00Z");
    private static final String TENANT = "default";

    @Test
    @DisplayName("payment success posts balanced 借清算往来 / 贷待结算+平台收入")
    void paymentSuccessPosting() {
        stubAccountLookups();
        LedgerVoucherEntity v = service().postPaymentSuccess(TENANT, "P1",
                new BigDecimal("100.00"), new BigDecimal("2.00"), T);
        assertEquals(new BigDecimal("100.00"), v.getTotalAmount());
        ArgumentCaptor<LedgerEntryEntity> entries = ArgumentCaptor.forClass(LedgerEntryEntity.class);
        org.mockito.Mockito.verify(entryRepository, org.mockito.Mockito.times(3)).save(entries.capture());
        List<LedgerEntryEntity> lines = entries.getAllValues();
        assertEquals(LedgerSubject.CLEARING_IN_TRANSIT, lines.get(0).getSubject());
        assertEquals(LedgerEntryEntity.LedgerSide.DEBIT, lines.get(0).getSide());
        assertEquals(LedgerSubject.MERCHANT_PENDING, lines.get(1).getSubject());
        assertEquals(new BigDecimal("98.00"), lines.get(1).getAmount());
        assertEquals(LedgerSubject.PLATFORM_INCOME, lines.get(2).getSubject());
    }

    @Test
    @DisplayName("unbalanced voucher is rejected (Iron Law 0)")
    void unbalancedRejected() {
        LedgerService svc = service();
        assertThrows(IllegalStateException.class, () -> svc.post(TENANT, "PAYMENT", "P1", List.of(
                new LedgerService.Line(LedgerSubject.CLEARING_IN_TRANSIT, LedgerEntryEntity.LedgerSide.DEBIT, new BigDecimal("10")),
                new LedgerService.Line(LedgerSubject.MERCHANT_PENDING, LedgerEntryEntity.LedgerSide.CREDIT, new BigDecimal("9"))
        ), T));
    }

    @Test
    @DisplayName("refund reverses payment posting direction")
    void refundPosting() {
        stubAccountLookups();
        service().postRefund(TENANT, "R1", new BigDecimal("50"), T);
        ArgumentCaptor<LedgerEntryEntity> entries = ArgumentCaptor.forClass(LedgerEntryEntity.class);
        org.mockito.Mockito.verify(entryRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        assertEquals(LedgerSubject.MERCHANT_PENDING, entries.getAllValues().get(0).getSubject());
        assertEquals(LedgerEntryEntity.LedgerSide.DEBIT, entries.getAllValues().get(0).getSide());
        assertEquals(LedgerSubject.CLEARING_IN_TRANSIT, entries.getAllValues().get(1).getSubject());
    }

    @Test
    @DisplayName("交易对账 match: 借已清算应收银行 / 贷清算往来")
    void channelClearedPosting() {
        stubAccountLookups();
        service().postChannelCleared(TENANT, "S1", new BigDecimal("300"), T);
        ArgumentCaptor<LedgerEntryEntity> entries = ArgumentCaptor.forClass(LedgerEntryEntity.class);
        org.mockito.Mockito.verify(entryRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        assertEquals(LedgerSubject.BANK_CLEARED_RECEIVABLE, entries.getAllValues().get(0).getSubject());
        assertEquals(LedgerSubject.CLEARING_IN_TRANSIT, entries.getAllValues().get(1).getSubject());
    }

    @Test
    @DisplayName("资金对账 match: 借银行存款备付金 / 贷已清算应收银行")
    void channelSettledPosting() {
        stubAccountLookups();
        service().postChannelSettled(TENANT, "S2", new BigDecimal("300"), T);
        ArgumentCaptor<LedgerEntryEntity> entries = ArgumentCaptor.forClass(LedgerEntryEntity.class);
        org.mockito.Mockito.verify(entryRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        assertEquals(LedgerSubject.BANK_RESERVE, entries.getAllValues().get(0).getSubject());
        assertEquals(LedgerSubject.BANK_CLEARED_RECEIVABLE, entries.getAllValues().get(1).getSubject());
    }

    @Test
    @DisplayName("长短款核销: both directions balanced")
    void writeOffBothDirections() {
        stubAccountLookups();
        LedgerService svc = service();
        svc.postWriteOff(TENANT, "D1", new BigDecimal("5"), true, T);
        svc.postWriteOff(TENANT, "D2", new BigDecimal("5"), false, T);
        org.mockito.Mockito.verify(entryRepository, org.mockito.Mockito.times(4)).save(any());
    }

    @Test
    @DisplayName("merchant settled: 借待结算商户 / 贷商户结算户")
    void merchantSettledPosting() {
        stubAccountLookups();
        service().postMerchantSettled(TENANT, "S3", new BigDecimal("98"), T);
        ArgumentCaptor<LedgerEntryEntity> entries = ArgumentCaptor.forClass(LedgerEntryEntity.class);
        org.mockito.Mockito.verify(entryRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        assertEquals(LedgerSubject.MERCHANT_PENDING, entries.getAllValues().get(0).getSubject());
        assertEquals(LedgerSubject.MERCHANT_SETTLEMENT, entries.getAllValues().get(1).getSubject());
    }

    @Test
    @DisplayName("aggregation folds detail vouchers into one AGGREGATED voucher")
    void aggregationFolds() {
        stubAccountLookups();
        LedgerVoucherRepository vouchers = voucherRepository;
        when(vouchers.findByTenantIdAndBizDateAndKind(TENANT, "2026-10-27",
                LedgerVoucherEntity.VoucherKind.DETAIL)).thenReturn(List.of(
                new LedgerVoucherEntity("V1", TENANT, "PAYMENT", "2026-10-27",
                        new BigDecimal("10"), LedgerVoucherEntity.VoucherKind.DETAIL, "", T),
                new LedgerVoucherEntity("V2", TENANT, "PAYMENT", "2026-10-27",
                        new BigDecimal("20"), LedgerVoucherEntity.VoucherKind.DETAIL, "", T)));
        LedgerVoucherEntity agg = service().aggregateDay(TENANT, "PAYMENT", "2026-10-27");
        assertNotNull(agg);
        assertEquals(new BigDecimal("30"), agg.getTotalAmount());
        assertEquals(LedgerVoucherEntity.VoucherKind.AGGREGATED, agg.getKind());
    }

    @Test
    @DisplayName("aggregation with no details returns null")
    void aggregationEmpty() {
        when(voucherRepository.findByTenantIdAndBizDateAndKind(TENANT, "2026-10-27",
                LedgerVoucherEntity.VoucherKind.DETAIL)).thenReturn(List.of());
        assertNull(service().aggregateDay(TENANT, "PAYMENT", "2026-10-27"));
    }

    @Test
    @DisplayName("account auto-created on first touch and balance applied per normal direction")
    void accountAutoCreated() {
        LedgerAccountEntity created = new LedgerAccountEntity(TENANT, LedgerSubject.MERCHANT_PENDING, T);
        lenient().when(accountRepository.findByTenantIdAndSubject(
                        org.mockito.ArgumentMatchers.eq(TENANT),
                        org.mockito.ArgumentMatchers.eq(LedgerSubject.MERCHANT_PENDING)))
                .thenReturn(Optional.of(created));
        lenient().when(accountRepository.findByTenantIdAndSubject(
                        org.mockito.ArgumentMatchers.eq(TENANT),
                        org.mockito.ArgumentMatchers.argThat(s2 -> s2 != LedgerSubject.MERCHANT_PENDING)))
                .thenReturn(Optional.empty());
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LedgerService svc = service();
        svc.postPaymentSuccess(TENANT, "P1", new BigDecimal("100.00"), new BigDecimal("2.00"), T);
        assertEquals(new BigDecimal("98.00"), created.getBalance(),
                "CREDIT_NORMAL subject credited 98 → balance +98");
    }
}
