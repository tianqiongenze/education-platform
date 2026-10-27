package com.ep.payment.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LedgerVoucherRepository extends JpaRepository<LedgerVoucherEntity, String> {

    List<LedgerVoucherEntity> findByTenantIdAndBizDateAndKind(String tenantId, String bizDate,
                                                              LedgerVoucherEntity.VoucherKind kind);

    @Query("select v from LedgerVoucherEntity v where v.tenantId = :tenantId "
            + "and v.bizDate = :bizDate and v.bizType = :bizType "
            + "and v.kind = com.ep.payment.ledger.LedgerVoucherEntity$VoucherKind.DETAIL")
    List<LedgerVoucherEntity> findDetailVouchers(@Param("tenantId") String tenantId,
                                                 @Param("bizDate") String bizDate,
                                                 @Param("bizType") String bizType);
}
