package com.ep.senior.health;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HealthRecordRepository extends JpaRepository<HealthRecord, Long> {

    List<HealthRecord> findByElderIdOrderByRecordedAtDesc(Long elderId);

    List<HealthRecord> findByElderIdAndIsAbnormalTrueOrderByRecordedAtDesc(Long elderId);
}
