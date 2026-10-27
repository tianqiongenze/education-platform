package com.ep.payment.recon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * D-239/A4: imported channel statement line (渠道账单数据 — one of the
 * "4 段数据": 账务/交易/清算/渠道账单). The reconciliation engine joins this
 * segment against platform payment records to produce 差异 (differences).
 */
@Entity
@Table(name = "ep_channel_statement")
public class ChannelStatementEntity {

    @Id
    @Column(name = "statement_line_id", length = 64)
    private String statementLineId;

    @Column(name = "channel_tx_id", nullable = false, length = 128)
    private String channelTxId;

    @Column(name = "payment_id", length = 64)
    private String paymentId; // channel's echo of our payment id, when present

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 16)
    private String status; // SUCCESS / FAILED (channel view)

    @Column(name = "biz_date", nullable = false, length = 10)
    private String bizDate;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    public ChannelStatementEntity() {
    }

    public ChannelStatementEntity(String statementLineId, String channelTxId, String paymentId,
                                  BigDecimal amount, String status, String bizDate, Instant importedAt) {
        this.statementLineId = statementLineId;
        this.channelTxId = channelTxId;
        this.paymentId = paymentId;
        this.amount = amount;
        this.status = status;
        this.bizDate = bizDate;
        this.importedAt = importedAt;
    }

    public String getStatementLineId() { return statementLineId; }
    public String getChannelTxId() { return channelTxId; }
    public String getPaymentId() { return paymentId; }
    public BigDecimal getAmount() { return amount; }
    public String getStatus() { return status; }
    public String getBizDate() { return bizDate; }
    public Instant getImportedAt() { return importedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChannelStatementEntity that)) return false;
        return Objects.equals(statementLineId, that.statementLineId);
    }

    @Override
    public int hashCode() { return Objects.hash(statementLineId); }
}
