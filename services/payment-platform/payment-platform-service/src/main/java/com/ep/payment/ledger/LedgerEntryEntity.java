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
 * D-238/A3: a single journal line (分录) within a {@link LedgerVoucherEntity}.
 * Detail rows are retained forever ("明细全留") — aggregation happens at the
 * voucher layer only ("入账聚合"), so traceability is never lost.
 */
@Entity
@Table(name = "ep_ledger_entry")
public class LedgerEntryEntity {

    @Id
    @Column(name = "entry_id", length = 64)
    private String entryId;

    @Column(name = "voucher_id", nullable = false, length = 64)
    private String voucherId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "subject_code", nullable = false, length = 8)
    @Enumerated(EnumType.STRING)
    private LedgerSubject subject;

    /** DEBIT or CREDIT. */
    @Column(nullable = false, length = 8)
    @Enumerated(EnumType.STRING)
    private LedgerSide side;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 32)
    private String bizType; // PAYMENT / REFUND / CHANNEL_CLEARED / MERCHANT_SETTLED / WRITE_OFF / AGGREGATED

    @Column(name = "ref_id", length = 64)
    private String refId; // paymentId / refundId / statementId / aggregate marker

    @Column(name = "biz_date", nullable = false, length = 10)
    private String bizDate; // yyyy-MM-dd, aggregation bucket

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public LedgerEntryEntity() {
    }

    public LedgerEntryEntity(String entryId, String voucherId, String tenantId,
                             LedgerSubject subject, LedgerSide side, BigDecimal amount,
                             String bizType, String refId, String bizDate, Instant createdAt) {
        this.entryId = entryId;
        this.voucherId = voucherId;
        this.tenantId = tenantId;
        this.subject = subject;
        this.side = side;
        this.amount = amount;
        this.bizType = bizType;
        this.refId = refId;
        this.bizDate = bizDate;
        this.createdAt = createdAt;
    }

    public enum LedgerSide { DEBIT, CREDIT }

    public String getEntryId() { return entryId; }
    public String getVoucherId() { return voucherId; }
    public String getTenantId() { return tenantId; }
    public LedgerSubject getSubject() { return subject; }
    public LedgerSide getSide() { return side; }
    public BigDecimal getAmount() { return amount; }
    public String getBizType() { return bizType; }
    public String getRefId() { return refId; }
    public String getBizDate() { return bizDate; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LedgerEntryEntity that)) return false;
        return Objects.equals(entryId, that.entryId);
    }

    @Override
    public int hashCode() { return Objects.hash(entryId); }
}
