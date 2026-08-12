package com.ep.senior.care;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.ep.senior.common.DomainEvent;
import com.ep.senior.common.SnowflakeIdGenerator;
import com.ep.senior.event.PulsarEventPublisher;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@DubboService
public class CarePlanServiceImpl implements CarePlanService {

    private static final Logger log = LoggerFactory.getLogger(CarePlanServiceImpl.class);

    private final CarePlanRepository repository;
    private final PulsarEventPublisher eventPublisher;

    public CarePlanServiceImpl(CarePlanRepository repository, PulsarEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    @SentinelResource(value = "CarePlanService.createPlan",
            blockHandler = "createPlanBlockHandler", fallback = "createPlanFallback")
    public CarePlan createPlan(CreateCarePlanCmd cmd) {
        Objects.requireNonNull(cmd, "cmd must not be null");
        requireNonBlank(cmd.getTitle(), "title");
        Objects.requireNonNull(cmd.getElderId(), "elderId");
        Objects.requireNonNull(cmd.getFacilityId(), "facilityId");
        Objects.requireNonNull(cmd.getTenantId(), "tenantId");

        long now = System.currentTimeMillis();
        CarePlan plan = new CarePlan();
        plan.setPlanId(SnowflakeIdGenerator.getInstance().nextId());
        plan.setElderId(cmd.getElderId());
        plan.setFacilityId(cmd.getFacilityId());
        plan.setTenantId(cmd.getTenantId());
        plan.setAssignedCaregiverId(cmd.getAssignedCaregiverId());
        plan.setTitle(cmd.getTitle());
        plan.setDescription(cmd.getDescription());
        plan.setCareItems(cmd.getCareItems());
        plan.setStartDate(cmd.getStartDate());
        plan.setEndDate(cmd.getEndDate());
        plan.setStatus(CarePlanStatus.DRAFT.getCode());
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);
        return repository.save(plan);
    }

    @Override
    @Transactional(readOnly = true)
    public CarePlan getPlanById(Long planId) {
        Objects.requireNonNull(planId, "planId must not be null");
        return repository.findById(planId).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CarePlan> listByElder(Long elderId) {
        Objects.requireNonNull(elderId, "elderId must not be null");
        return repository.findByElderIdOrderByPlanIdDesc(elderId);
    }

    @Override
    @Transactional
    public void activatePlan(Long planId) {
        Objects.requireNonNull(planId, "planId must not be null");
        CarePlan plan = repository.findById(planId)
                .orElseThrow(() -> new IllegalStateException("CarePlan not found: " + planId));
        plan.activate();
        repository.save(plan);
        publishEvents(plan);
    }

    @Override
    @Transactional
    public void completePlan(Long planId) {
        Objects.requireNonNull(planId, "planId must not be null");
        CarePlan plan = repository.findById(planId)
                .orElseThrow(() -> new IllegalStateException("CarePlan not found: " + planId));
        plan.complete();
        repository.save(plan);
        publishEvents(plan);
    }

    @Override
    @Transactional
    public void archivePlan(Long planId) {
        Objects.requireNonNull(planId, "planId must not be null");
        CarePlan plan = repository.findById(planId)
                .orElseThrow(() -> new IllegalStateException("CarePlan not found: " + planId));
        plan.archive();
        repository.save(plan);
        publishEvents(plan);
    }

    public CarePlan createPlanBlockHandler(CreateCarePlanCmd cmd, com.alibaba.csp.sentinel.slots.block.BlockException ex) {
        throw new IllegalStateException("CarePlan creation rate-limited: " + ex.getClass().getSimpleName());
    }

    public CarePlan createPlanFallback(CreateCarePlanCmd cmd, Throwable t) {
        throw new IllegalStateException("CarePlan creation unavailable: " + t.getMessage());
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private void publishEvents(CarePlan plan) {
        List<DomainEvent> events = plan.pullEvents();
        for (DomainEvent event : events) {
            log.info("D-150: domain event recorded: {}", event);
            eventPublisher.publish(event);
        }
    }
}
