package com.ep.payment.recon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * D-239/A4: one reconciliation difference (差错). The three standard 对账
 * dimensions produce these records; resolution posts the matching ledger
 * write-off (长短款核销 / 在途平账).
 */
@Entity
@Table(name = "ep_recon_difference")
public class ReconDifferenceEntity {

    @Id
    @Column(name = "diff_id", length = 64)
    private String diffId;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private DiffKind kind;

    /** 交易对账 / 资金对账 / 余额对账 (the 3 套对账). */
    @Column(nullable = false, length = 24)
    @Enumerated(EnumType.STRING)
    private ReconDimension dimension;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "channel_tx_id", length = 128)
    private String channelTxId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    private String bizDate;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private DiffStatus status; // OPEN / RESOLVED_IN_TRANSIT / RESOLVED_WRITE_OFF

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public enum DiffKind {
        /** Platform has the payment, channel does not → 在途 (channel one-sided). */
        PLATFORM_ONLY,
        /** Channel has the record, platform does not → 长款 (platform one-sided). */
        CHANNEL_ONLY,
        /** Both sides present but amounts differ. */
        AMOUNT_MISMATCH
    }

    public enum ReconDimension { TRANSACTION, FUND, BALANCE }

    public enum DiffStatus { OPEN, RESOLVED_IN_TRANSIT, RESOLVED_WRITE_OFF }

    public ReconDifferenceEntity() {
    }

    public ReconDifferenceEntity(String diffId, DiffKind kind, ReconDimension dimension,
                                 String paymentId, String channelTxId, BigDecimal amount,
                                 String bizDate, DiffStatus status, Instant resolvedAt) {
        this.diffId = diffId;
        this.kind = kind;
        this.dimension = dimension;
        this.paymentId = paymentId;
        this.channelTxId = channelTxId;
        this.amount = amount;
        this.bizDate = bizDate;
        this.status = status;
        this.resolvedAt = resolvedAt;
    }

    public String getDiffId() { return diffId; }
    public DiffKind getKind() { return kind; }
    public ReconDimension getDimension() { return dimension; }
    public String getPaymentId() { return paymentId; }
    public String getChannelTxId() { return channelTxId; }
    public BigDecimal getAmount() { return amount; }
    public String getBizDate() { return bizDate; }
    public DiffStatus getStatus() { return status; }
    public Instant getResolvedAt() { return resolvedAt; }

    public void markResolved(DiffStatus resolution, Instant at) {
        this.status = resolution;
        this.resolvedAt = at;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReconDifferenceEntity that)) return false;
        return Objects.equals(diffId, that.diffId);
    }

    @Override
    public int hashCode() { return Objects.hash(diffId); }
}
