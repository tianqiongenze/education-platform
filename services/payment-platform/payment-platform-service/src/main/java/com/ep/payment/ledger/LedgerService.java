package com.ep.payment.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * D-238/A3: ledger posting service — the double-entry engine behind the
 * "5 套账务底座" (「AI支付」文档 · 123457 清结算模型).
 *
 * <p>Core invariants:
 * <ol>
 *   <li>Every voucher is balanced: Σ(debit) == Σ(credit).</li>
 *   <li>Every subject touched has an account row (auto-created on first
 *       touch — "每一笔钱从进来那一刻起，就要有一个明确的科目等着它").</li>
 *   <li>Detail rows are retained forever; the daily aggregation job folds
 *       DETAIL vouchers into one AGGREGATED voucher per
 *       (tenant, bizType, bizDate) — "明细全留，入账聚合".</li>
 * </ol>
 *
 * <p>Posting templates map business events to entries (the 7 processing steps
 * of the reference model):
 * <pre>
 *   payment success      : 借 清算往来        / 贷 待结算商户 (−fee) + 贷 平台收入 (+fee)
 *   refund               : 借 待结算商户      / 贷 清算往来
 *   交易对账 match       : 借 已清算应收银行  / 贷 清算往来        (平支付在途)
 *   渠道资金对账 match   : 借 银行存款备付金  / 贷 已清算应收银行
 *   商户结算             : 借 待结算商户      / 贷 商户结算户
 *   长短款核销           : 借/贷 清算过渡户   ↔ 贷/借 长短款核销
 * </pre>
 */
@Service
public class LedgerService {

    private final LedgerAccountRepository accountRepository;
    private final LedgerVoucherRepository voucherRepository;
    private final LedgerEntryRepository entryRepository;

    public LedgerService(LedgerAccountRepository accountRepository,
                         LedgerVoucherRepository voucherRepository,
                         LedgerEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.voucherRepository = voucherRepository;
        this.entryRepository = entryRepository;
    }

    /** One journal line. */
    public record Line(LedgerSubject subject, LedgerEntryEntity.LedgerSide side, BigDecimal amount) {
        public Line {
            if (amount == null || amount.signum() <= 0) {
                throw new IllegalArgumentException("ledger line amount must be > 0");
            }
        }
    }

    /**
     * Posts a balanced DETAIL voucher and updates account balances.
     *
     * @throws IllegalStateException when the lines do not balance — the caller
     *                               (posting template) is buggy, never silently
     *                               swallowed (Iron Law 0).
     */
    @Transactional
    public LedgerVoucherEntity post(String tenantId, String bizType, String refId,
                                    List<Line> lines, Instant at) {
        if (lines == null || lines.size() < 2) {
            throw new IllegalArgumentException("a voucher needs at least two lines");
        }
        BigDecimal debits = lines.stream()
                .filter(l -> l.side() == LedgerEntryEntity.LedgerSide.DEBIT)
                .map(Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = lines.stream()
                .filter(l -> l.side() == LedgerEntryEntity.LedgerSide.CREDIT)
                .map(Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debits.compareTo(credits) != 0) {
            throw new IllegalStateException(
                    "unbalanced voucher: debit=" + debits + " credit=" + credits);
        }

        String voucherId = "V" + UUID.randomUUID().toString().replace("-", "");
        String bizDate = LocalDate.ofInstant(at, ZoneId.of("UTC")).toString();
        LedgerVoucherEntity voucher = new LedgerVoucherEntity(
                voucherId, tenantId, bizType, bizDate, debits,
                LedgerVoucherEntity.VoucherKind.DETAIL, bizType + " " + refId, at);
        voucherRepository.save(voucher);

        for (Line line : lines) {
            LedgerEntryEntity entry = new LedgerEntryEntity(
                    "E" + UUID.randomUUID().toString().replace("-", ""),
                    voucherId, tenantId, line.subject(), line.side(), line.amount(),
                    bizType, refId, bizDate, at);
            entryRepository.save(entry);
            applyToAccount(tenantId, line.subject(), line.side(), line.amount(), at);
        }
        return voucher;
    }

    /** Signed balance delta: DEBIT increases DEBIT_NORMAL accounts, decreases CREDIT_NORMAL ones. */
    private void applyToAccount(String tenantId, LedgerSubject subject,
                                LedgerEntryEntity.LedgerSide side, BigDecimal amount, Instant at) {
        LedgerAccountEntity account = accountRepository
                .findByTenantIdAndSubject(tenantId, subject)
                .orElseGet(() -> accountRepository.save(new LedgerAccountEntity(tenantId, subject, at)));
        int sign = switch (subject.normalDirection()) {
            case DEBIT_NORMAL -> side == LedgerEntryEntity.LedgerSide.DEBIT ? 1 : -1;
            case CREDIT_NORMAL -> side == LedgerEntryEntity.LedgerSide.CREDIT ? 1 : -1;
            case BOTH -> side == LedgerEntryEntity.LedgerSide.DEBIT ? 1 : -1;
        };
        BigDecimal signed = amount.multiply(BigDecimal.valueOf(sign));
        account.apply(signed, at);
        accountRepository.save(account);
    }

    // ------------------------------------------------------------------
    // Posting templates (the 7 processing steps, 123457 model)
    // ------------------------------------------------------------------

    /** Step 1 (支付交易环节): payment SUCCESS → 借 清算往来 / 贷 待结算商户 + 贷 平台收入. */
    @Transactional
    public LedgerVoucherEntity postPaymentSuccess(String tenantId, String paymentId,
                                                  BigDecimal amount, BigDecimal fee, Instant at) {
        BigDecimal merchantAmount = amount.subtract(fee);
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(LedgerSubject.CLEARING_IN_TRANSIT, LedgerEntryEntity.LedgerSide.DEBIT, amount));
        lines.add(new Line(LedgerSubject.MERCHANT_PENDING, LedgerEntryEntity.LedgerSide.CREDIT, merchantAmount));
        if (fee.signum() > 0) {
            lines.add(new Line(LedgerSubject.PLATFORM_INCOME, LedgerEntryEntity.LedgerSide.CREDIT, fee));
        }
        return post(tenantId, "PAYMENT", paymentId, lines, at);
    }

    /** Refund: 借 待结算商户 / 贷 清算往来 (reverses the payment posting). */
    @Transactional
    public LedgerVoucherEntity postRefund(String tenantId, String refundId,
                                          BigDecimal amount, Instant at) {
        return post(tenantId, "REFUND", refundId, List.of(
                new Line(LedgerSubject.MERCHANT_PENDING, LedgerEntryEntity.LedgerSide.DEBIT, amount),
                new Line(LedgerSubject.CLEARING_IN_TRANSIT, LedgerEntryEntity.LedgerSide.CREDIT, amount)), at);
    }

    /** Step 2 (渠道清算环节/交易对账 match): 借 已清算应收银行 / 贷 清算往来. */
    @Transactional
    public LedgerVoucherEntity postChannelCleared(String tenantId, String statementId,
                                                  BigDecimal amount, Instant at) {
        return post(tenantId, "CHANNEL_CLEARED", statementId, List.of(
                new Line(LedgerSubject.BANK_CLEARED_RECEIVABLE, LedgerEntryEntity.LedgerSide.DEBIT, amount),
                new Line(LedgerSubject.CLEARING_IN_TRANSIT, LedgerEntryEntity.LedgerSide.CREDIT, amount)), at);
    }

    /** Step 6 (渠道结算环节/资金对账 match): 借 银行存款备付金 / 贷 已清算应收银行. */
    @Transactional
    public LedgerVoucherEntity postChannelSettled(String tenantId, String settlementId,
                                                  BigDecimal amount, Instant at) {
        return post(tenantId, "CHANNEL_SETTLED", settlementId, List.of(
                new Line(LedgerSubject.BANK_RESERVE, LedgerEntryEntity.LedgerSide.DEBIT, amount),
                new Line(LedgerSubject.BANK_CLEARED_RECEIVABLE, LedgerEntryEntity.LedgerSide.CREDIT, amount)), at);
    }

    /** Step 4 (商户结算环节): 借 待结算商户 / 贷 商户结算户. */
    @Transactional
    public LedgerVoucherEntity postMerchantSettled(String tenantId, String settlementId,
                                                   BigDecimal amount, Instant at) {
        return post(tenantId, "MERCHANT_SETTLED", settlementId, List.of(
                new Line(LedgerSubject.MERCHANT_PENDING, LedgerEntryEntity.LedgerSide.DEBIT, amount),
                new Line(LedgerSubject.MERCHANT_SETTLEMENT, LedgerEntryEntity.LedgerSide.CREDIT, amount)), at);
    }

    /** Step 7 (长短款核销): 借 清算过渡户 / 贷 长短款核销 (or the mirror for short money). */
    @Transactional
    public LedgerVoucherEntity postWriteOff(String tenantId, String diffId,
                                            BigDecimal amount, boolean longMoney, Instant at) {
        List<Line> lines = longMoney
                ? List.of(new Line(LedgerSubject.CLEARING_TRANSIT, LedgerEntryEntity.LedgerSide.DEBIT, amount),
                          new Line(LedgerSubject.LONG_SHORT_WRITE_OFF, LedgerEntryEntity.LedgerSide.CREDIT, amount))
                : List.of(new Line(LedgerSubject.LONG_SHORT_WRITE_OFF, LedgerEntryEntity.LedgerSide.DEBIT, amount),
                          new Line(LedgerSubject.CLEARING_TRANSIT, LedgerEntryEntity.LedgerSide.CREDIT, amount));
        return post(tenantId, "WRITE_OFF", diffId, lines, at);
    }

    // ------------------------------------------------------------------
    // Aggregation ("明细全留，入账聚合")
    // ------------------------------------------------------------------

    /**
     * Folds all DETAIL payment/refund vouchers of (tenant, bizDate) into one
     * AGGREGATED voucher per bizType. Detail rows are retained unchanged.
     *
     * @return the aggregated voucher, or null when there was nothing to fold.
     */
    @Transactional
    public LedgerVoucherEntity aggregateDay(String tenantId, String bizType, String bizDate) {
        List<LedgerVoucherEntity> details =
                voucherRepository.findByTenantIdAndBizDateAndKind(tenantId, bizDate,
                                LedgerVoucherEntity.VoucherKind.DETAIL)
                        .stream()
                        .filter(v -> v.getBizType().equals(bizType))
                        .toList();
        if (details.isEmpty()) {
            return null;
        }
        BigDecimal total = details.stream()
                .map(LedgerVoucherEntity::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String aggId = "AG" + bizType + "-" + bizDate + "-" + UUID.randomUUID().toString().substring(0, 8);
        LedgerVoucherEntity aggregated = new LedgerVoucherEntity(
                aggId, tenantId, bizType, bizDate, total,
                LedgerVoucherEntity.VoucherKind.AGGREGATED,
                "aggregated " + details.size() + " detail vouchers", Instant.now());
        voucherRepository.save(aggregated);
        return aggregated;
    }
}
