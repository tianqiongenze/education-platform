package com.ep.payment.ledger;

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
 * D-238/A3: accounting voucher (记账凭证) header. The lines live in
 * {@link LedgerEntryEntity}; the invariant enforced by {@link LedgerService} is
 * Σ(debit) == Σ(credit) per voucher.
 *
 * <p>Aggregation ("聚合凭证"): a daily job folds many DETAIL vouchers of the
 * same (tenant, subject, bizType, bizDate) into one AGGREGATED voucher while
 * retaining every detail row — "明细全留，入账聚合".
 */
@Entity
@Table(name = "ep_ledger_voucher")
public class LedgerVoucherEntity {

    @Id
    @Column(name = "voucher_id", length = 64)
    private String voucherId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 32)
    private String bizType; // PAYMENT / REFUND / CHANNEL_CLEARED / MERCHANT_SETTLED / WRITE_OFF / AGGREGATED

    @Column(name = "biz_date", nullable = false, length = 10)
    private String bizDate;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private VoucherKind kind; // DETAIL / AGGREGATED

    @Column(name = "summary", length = 256)
    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public enum VoucherKind { DETAIL, AGGREGATED }

    public LedgerVoucherEntity() {
    }

    public LedgerVoucherEntity(String voucherId, String tenantId, String bizType,
                               String bizDate, BigDecimal totalAmount, VoucherKind kind,
                               String summary, Instant createdAt) {
        this.voucherId = voucherId;
        this.tenantId = tenantId;
        this.bizType = bizType;
        this.bizDate = bizDate;
        this.totalAmount = totalAmount;
        this.kind = kind;
        this.summary = summary;
        this.createdAt = createdAt;
    }

    public String getVoucherId() { return voucherId; }
    public String getTenantId() { return tenantId; }
    public String getBizType() { return bizType; }
    public String getBizDate() { return bizDate; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public VoucherKind getKind() { return kind; }
    public String getSummary() { return summary; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LedgerVoucherEntity that)) return false;
        return Objects.equals(voucherId, that.voucherId);
    }

    @Override
    public int hashCode() { return Objects.hash(voucherId); }
}
