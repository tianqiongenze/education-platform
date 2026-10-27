package com.ep.payment.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccountEntity, String> {

    Optional<LedgerAccountEntity> findByTenantIdAndSubject(String tenantId, LedgerSubject subject);

    List<LedgerAccountEntity> findByTenantId(String tenantId);
}
