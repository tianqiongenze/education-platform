package com.ep.senior.care;

import java.util.List;

public interface CarePlanService {

    CarePlan createPlan(CreateCarePlanCmd cmd);

    CarePlan getPlanById(Long planId);

    List<CarePlan> listByElder(Long elderId);

    void activatePlan(Long planId);

    void completePlan(Long planId);

    void archivePlan(Long planId);
}
