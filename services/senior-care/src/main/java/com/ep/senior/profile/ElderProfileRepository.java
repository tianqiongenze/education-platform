package com.ep.senior.profile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ElderProfileRepository extends JpaRepository<ElderProfile, Long> {

    Page<ElderProfile> findAllByOrderByElderIdDesc(Pageable pageable);

    List<ElderProfile> findByFacilityIdAndStatusOrderByElderIdDesc(Long facilityId, Integer status);

    List<ElderProfile> findByNameContainingOrderByElderIdDesc(String keyword);

    /** D-221: probe for an existing profile created with the same idempotency key. */
    Optional<ElderProfile> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);
}
