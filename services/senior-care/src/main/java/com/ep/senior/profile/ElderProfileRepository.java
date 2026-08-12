package com.ep.senior.profile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ElderProfileRepository extends JpaRepository<ElderProfile, Long> {

    Page<ElderProfile> findAllByOrderByElderIdDesc(Pageable pageable);

    List<ElderProfile> findByFacilityIdAndStatusOrderByElderIdDesc(Long facilityId, Integer status);

    List<ElderProfile> findByNameContainingOrderByElderIdDesc(String keyword);
}
