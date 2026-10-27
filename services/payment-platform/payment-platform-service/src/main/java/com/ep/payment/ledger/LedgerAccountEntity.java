package com.ep.payment.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * D-238/A3: ledger account (科目余额表) — one row per subject per tenant.
 * Balance maintenance is owned by {@link LedgerService#post}; this entity is a
 * plain JPA aggregate with optimistic locking.
 */
@Entity
@Table(name = "ep_ledger_account")
public class LedgerAccountEntity {

    @Id
    @Column(name = "account_key", length = 96)
    private String accountKey; // tenantId + ":" + subjectCode

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "subject_code", nullable = false, length = 8)
    @Enumerated(EnumType.STRING)
    private LedgerSubject subject;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version")
    private Integer lockVersion;

    public LedgerAccountEntity() {
    }

    public LedgerAccountEntity(String tenantId, LedgerSubject subject, Instant now) {
        this.accountKey = tenantId + ":" + subject.code();
        this.tenantId = tenantId;
        this.subject = subject;
        this.balance = BigDecimal.ZERO;
        this.updatedAt = now;
    }

    /** Applies one signed delta (debit positive on DEBIT_NORMAL, credit positive otherwise handled by caller). */
    public void apply(BigDecimal signedDelta, Instant now) {
        this.balance = this.balance.add(signedDelta);
        this.updatedAt = now;
    }

    public String getAccountKey() { return accountKey; }
    public String getTenantId() { return tenantId; }
    public LedgerSubject getSubject() { return subject; }
    public BigDecimal getBalance() { return balance; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Integer getLockVersion() { return lockVersion; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LedgerAccountEntity that)) return false;
        return Objects.equals(accountKey, that.accountKey);
    }

    @Override
    public int hashCode() { return Objects.hash(accountKey); }
}
