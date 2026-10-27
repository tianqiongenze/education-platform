package com.ep.payment.recon;

import com.ep.payment.api.PaymentStatus;
import com.ep.payment.ledger.LedgerService;
import com.ep.payment.repository.PaymentJpaRepository;
import com.ep.payment.entity.PaymentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * D-239/A4: 交易对账 engine (「AI支付」文档 · 123457 模型 — "对账中心获取渠道清算
 * 文件与平台交易记录进行核对"). Joins platform SUCCESS payments against the
 * imported channel statement for a biz date and produces classified 差异.
 *
 * <p>Matching key: channelTxId (echoed by the channel). Classification:
 * <ul>
 *   <li>PLATFORM_ONLY — platform SUCCESS payment without a channel line → 在途
 *       (money charged but not yet confirmed by the channel statement).</li>
 *   <li>CHANNEL_ONLY — channel line without a platform payment → 长款 (possible
 *       duplicate charge or orphan channel record).</li>
 *   <li>AMOUNT_MISMATCH — both present but amounts differ → 差错 (fee mismatch,
 *       partial capture, or tampering — always investigated).</li>
 *   <li>MATCH — balanced; posts 借 已清算应收银行 / 贷 清算往来 (交易对账 match
 *       posting, step 2 of the 7 processing steps).</li>
 * </ul>
 *
 * <p>Amounts are compared at exact equality; fee-splitting happens at settlement
 * stage, not at transaction reconciliation.
 */
@Service
public class TransactionReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(TransactionReconciliationService.class);

    private final ChannelStatementRepository statementRepository;
    private final PaymentJpaRepository paymentRepository;
    private final ReconDifferenceRepository differenceRepository;
    private final LedgerService ledgerService;

    public record ReconResult(String bizDate, int matched, int platformOnly,
                              int channelOnly, int amountMismatch) {
        public int totalDifferences() {
            return platformOnly + channelOnly + amountMismatch;
        }
    }

    public TransactionReconciliationService(ChannelStatementRepository statementRepository,
                                            PaymentJpaRepository paymentRepository,
                                            ReconDifferenceRepository differenceRepository,
                                            LedgerService ledgerService) {
        this.statementRepository = statementRepository;
        this.paymentRepository = paymentRepository;
        this.differenceRepository = differenceRepository;
        this.ledgerService = ledgerService;
    }

    /**
     * Import one channel statement line (mock channel emits these; a real
     * channel delivers a daily file). Idempotent by statementLineId.
     */
    @Transactional
    public ChannelStatementEntity importStatementLine(String statementLineId, String channelTxId,
                                                      String paymentId, BigDecimal amount,
                                                      String status, String bizDate) {
        ChannelStatementEntity line = new ChannelStatementEntity(
                statementLineId, channelTxId, paymentId, amount, status, bizDate, Instant.now());
        return statementRepository.save(line);
    }

    /**
     * Runs 交易对账 for the given biz date. Produces difference records for
     * every mismatch and posts the 交易对账-match ledger voucher per matched
     * payment.
     */
    @Transactional
    public ReconResult reconcile(String bizDate) {
        List<ChannelStatementEntity> statements =
                statementRepository.findByBizDate(bizDate);
        Map<String, ChannelStatementEntity> byChannelTx = new HashMap<>();
        for (ChannelStatementEntity s : statements) {
            byChannelTx.put(s.getChannelTxId(), s);
        }

        Instant now = Instant.now();
        List<PaymentEntity> successPayments = paymentRepository.findAll().stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCESS)
                .filter(p -> bizDate.equals(LocalDate.ofInstant(
                        p.getPaidAt() == null ? Instant.EPOCH : p.getPaidAt(),
                        ZoneId.of("UTC")).toString()))
                .toList();

        int matched = 0, platformOnly = 0, channelOnly = 0, mismatch = 0;
        List<ChannelStatementEntity> consumed = new ArrayList<>();

        for (PaymentEntity p : successPayments) {
            ChannelStatementEntity s = p.getChannelTxId() == null ? null
                    : byChannelTx.get(p.getChannelTxId());
            if (s == null) {
                saveDiff(ReconDifferenceEntity.DiffKind.PLATFORM_ONLY, p, null, bizDate, now);
                platformOnly++;
            } else if (s.getAmount().compareTo(p.getAmount()) != 0) {
                saveDiff(ReconDifferenceEntity.DiffKind.AMOUNT_MISMATCH, p, s, bizDate, now);
                mismatch++;
                consumed.add(s);
            } else {
                matched++;
                consumed.add(s);
                // 交易对账 match → 平支付在途 (step 2 of 7)
                ledgerService.postChannelCleared(p.getTenantId(),
                        "RC-" + s.getStatementLineId(), p.getAmount(), now);
            }
        }

        for (ChannelStatementEntity s : statements) {
            if (!consumed.contains(s)) {
                saveDiff(ReconDifferenceEntity.DiffKind.CHANNEL_ONLY, null, s, bizDate, now);
                channelOnly++;
            }
        }

        log.info("[recon] bizDate={} matched={} platformOnly={} channelOnly={} mismatch={}",
                bizDate, matched, platformOnly, channelOnly, mismatch);
        return new ReconResult(bizDate, matched, platformOnly, channelOnly, mismatch);
    }

    private void saveDiff(ReconDifferenceEntity.DiffKind kind, PaymentEntity p,
                          ChannelStatementEntity s, String bizDate, Instant now) {
        BigDecimal amount = p != null ? p.getAmount()
                : (s != null ? s.getAmount() : BigDecimal.ZERO);
        differenceRepository.save(new ReconDifferenceEntity(
                "D" + UUID.randomUUID().toString().replace("-", ""),
                kind, ReconDifferenceEntity.ReconDimension.TRANSACTION,
                p != null ? p.getPaymentId() : null,
                s != null ? s.getChannelTxId() : null,
                amount, bizDate,
                ReconDifferenceEntity.DiffStatus.OPEN, null));
    }

    /**
     * Resolve an OPEN difference: PLATFORM_ONLY → mark in-transit resolved after
     * channel confirmation (no posting — the money was always accounted);
     * CHANNEL_ONLY / AMOUNT_MISMATCH → 长短款核销 posting then resolve.
     */
    @Transactional
    public String resolveDifference(String diffId, ReconDifferenceEntity.DiffStatus resolution) {
        ReconDifferenceEntity diff = differenceRepository.findById(diffId)
                .orElseThrow(() -> new IllegalArgumentException("difference not found: " + diffId));
        if (resolution == ReconDifferenceEntity.DiffStatus.RESOLVED_WRITE_OFF) {
            ledgerService.postWriteOff(diff.getPaymentId() == null ? diff.getPaymentId() : diff.getPaymentId(),
                    diff.getDiffId(), diff.getAmount(),
                    diff.getKind() == ReconDifferenceEntity.DiffKind.CHANNEL_ONLY,
                    Instant.now());
        }
        diff.markResolved(resolution, Instant.now());
        differenceRepository.save(diff);
        return diff.getStatus().name();
    }
}
