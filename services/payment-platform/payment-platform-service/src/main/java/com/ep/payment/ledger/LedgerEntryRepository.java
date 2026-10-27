package com.ep.payment.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, String> {

    List<LedgerEntryEntity> findByVoucherId(String voucherId);

    List<LedgerEntryEntity> findByTenantIdAndSubjectAndBizDate(String tenantId, LedgerSubject subject,
                                                               String bizDate);
}
