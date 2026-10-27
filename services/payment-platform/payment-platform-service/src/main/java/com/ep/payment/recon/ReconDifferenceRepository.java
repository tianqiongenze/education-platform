package com.ep.payment.recon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconDifferenceRepository extends JpaRepository<ReconDifferenceEntity, String> {

    List<ReconDifferenceEntity> findByBizDateAndStatus(String bizDate, ReconDifferenceEntity.DiffStatus status);

    List<ReconDifferenceEntity> findByBizDate(String bizDate);
}
