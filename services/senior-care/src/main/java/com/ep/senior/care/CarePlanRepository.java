package com.ep.senior.care;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CarePlanRepository extends JpaRepository<CarePlan, Long> {

    List<CarePlan> findByElderIdOrderByPlanIdDesc(Long elderId);

    List<CarePlan> findByElderIdAndStatusOrderByPlanIdDesc(Long elderId, Integer status);
}
