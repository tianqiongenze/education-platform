package com.ep.senior.health;

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
public class HealthRecordServiceImpl implements HealthRecordService {

    private static final Logger log = LoggerFactory.getLogger(HealthRecordServiceImpl.class);

    private final HealthRecordRepository repository;
    private final PulsarEventPublisher eventPublisher;

    public HealthRecordServiceImpl(HealthRecordRepository repository, PulsarEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    @SentinelResource(value = "HealthRecordService.createRecord",
            blockHandler = "createRecordBlockHandler", fallback = "createRecordFallback")
    public HealthRecord createRecord(CreateHealthRecordCmd cmd) {
        Objects.requireNonNull(cmd, "cmd must not be null");
        Objects.requireNonNull(cmd.getElderId(), "elderId");
        Objects.requireNonNull(cmd.getFacilityId(), "facilityId");
        Objects.requireNonNull(cmd.getTenantId(), "tenantId");

        long now = System.currentTimeMillis();
        HealthRecord record = new HealthRecord();
        record.setRecordId(SnowflakeIdGenerator.getInstance().nextId());
        record.setElderId(cmd.getElderId());
        record.setFacilityId(cmd.getFacilityId());
        record.setTenantId(cmd.getTenantId());
        record.setSystolicBp(cmd.getSystolicBp());
        record.setDiastolicBp(cmd.getDiastolicBp());
        record.setHeartRate(cmd.getHeartRate());
        record.setBloodSugar(cmd.getBloodSugar());
        record.setTemperature(cmd.getTemperature());
        record.setSpo2(cmd.getSpo2());
        record.setNotes(cmd.getNotes());
        record.setRecordedAt(now);
        record.setCreatedAt(now);
        record.evaluateAbnormal(); // sets isAbnormal + records event if abnormal
        HealthRecord saved = repository.save(record);
        publishEvents(saved);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public HealthRecord getRecordById(Long recordId) {
        Objects.requireNonNull(recordId, "recordId must not be null");
        return repository.findById(recordId).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HealthRecord> listByElder(Long elderId) {
        Objects.requireNonNull(elderId, "elderId must not be null");
        return repository.findByElderIdOrderByRecordedAtDesc(elderId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HealthRecord> listAbnormalByElder(Long elderId) {
        Objects.requireNonNull(elderId, "elderId must not be null");
        return repository.findByElderIdAndIsAbnormalTrueOrderByRecordedAtDesc(elderId);
    }

    public HealthRecord createRecordBlockHandler(CreateHealthRecordCmd cmd, com.alibaba.csp.sentinel.slots.block.BlockException ex) {
        throw new IllegalStateException("Health record creation rate-limited: " + ex.getClass().getSimpleName());
    }

    public HealthRecord createRecordFallback(CreateHealthRecordCmd cmd, Throwable t) {
        throw new IllegalStateException("Health record creation unavailable: " + t.getMessage());
    }

    private void publishEvents(HealthRecord record) {
        List<DomainEvent> events = record.pullEvents();
        for (DomainEvent event : events) {
            log.info("D-149: domain event recorded: {}", event);
            eventPublisher.publish(event);
        }
    }
}
