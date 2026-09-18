package com.ep.senior.profile;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.ep.cache.CacheService;
import com.ep.senior.common.DomainEvent;
import com.ep.senior.common.SnowflakeIdGenerator;
import com.ep.senior.event.PulsarEventPublisher;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * D-148: ElderProfile service implementation.
 * <p>
 * D-182: getElderById cached via ep-cache SDK (L1 Caffeine + L2 Redis +
 * Pub/Sub broadcast). Writes evict the affected cache entry.
 */
@DubboService
public class ElderProfileServiceImpl implements ElderProfileService {

    private static final Logger log = LoggerFactory.getLogger(ElderProfileServiceImpl.class);

    private static final String CACHE_ELDER = "senior:elder";
    private static final long ELDER_TTL_SEC = 600;

    private final ElderProfileRepository repository;
    private final PulsarEventPublisher eventPublisher;
    private final CacheService cacheService;

    public ElderProfileServiceImpl(ElderProfileRepository repository,
                                   PulsarEventPublisher eventPublisher,
                                   CacheService cacheService) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.cacheService = cacheService;
    }

    @Override
    @Transactional
    @SentinelResource(value = "ElderProfileService.createElder",
            blockHandler = "createElderBlockHandler", fallback = "createElderFallback")
    public ElderProfile createElder(CreateElderCmd cmd) {
        Objects.requireNonNull(cmd, "cmd must not be null");
        requireNonBlank(cmd.getName(), "name");
        Objects.requireNonNull(cmd.getFacilityId(), "facilityId");
        Objects.requireNonNull(cmd.getTenantId(), "tenantId");

        // D-221: probe-pattern idempotency — a retried create with the same
        // business key returns the original profile instead of duplicating it.
        // Blank keys are normalised to null so they never enter the unique index.
        String idemKey = cmd.getIdempotencyKey() == null || cmd.getIdempotencyKey().isBlank()
                ? null : cmd.getIdempotencyKey().trim();
        if (idemKey != null) {
            ElderProfile existing = repository
                    .findByTenantIdAndIdempotencyKey(cmd.getTenantId(), idemKey)
                    .orElse(null);
            if (existing != null) {
                log.info("D-221: createElder idempotent replay for tenantId={} key={}: returning elderId={}",
                        cmd.getTenantId(), idemKey, existing.getElderId());
                return existing;
            }
        }

        long now = System.currentTimeMillis();
        ElderProfile elder = new ElderProfile();
        elder.setElderId(SnowflakeIdGenerator.getInstance().nextId());
        elder.setFacilityId(cmd.getFacilityId());
        elder.setTenantId(cmd.getTenantId());
        elder.setName(cmd.getName());
        elder.setGender(cmd.getGender());
        elder.setBirthDate(cmd.getBirthDate());
        elder.setIdCard(cmd.getIdCard());
        elder.setPhone(cmd.getPhone());
        elder.setEmergencyContact(cmd.getEmergencyContact());
        elder.setAddress(cmd.getAddress());
        elder.setNotes(cmd.getNotes());
        elder.setIdempotencyKey(idemKey);
        elder.setStatus(ElderStatus.ACTIVE.getCode());
        elder.setCreatedAt(now);
        elder.setUpdatedAt(now);
        ElderProfile saved = repository.save(elder);
        cacheService.evict(CACHE_ELDER, String.valueOf(saved.getElderId()));
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public ElderProfile getElderById(Long elderId) {
        Objects.requireNonNull(elderId, "elderId must not be null");
        return cacheService.getOrLoad(CACHE_ELDER, String.valueOf(elderId),
                () -> repository.findById(elderId).orElse(null), ELDER_TTL_SEC, true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ElderProfile> listElders(Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : size;
        Page<ElderProfile> result = repository.findAllByOrderByElderIdDesc(PageRequest.of(p - 1, s));
        return new ArrayList<>(result.getContent());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ElderProfile> searchElders(String keyword) {
        requireNonBlank(keyword, "keyword");
        return repository.findByNameContainingOrderByElderIdDesc(keyword);
    }

    @Override
    @Transactional
    public void pauseElder(Long elderId) {
        Objects.requireNonNull(elderId, "elderId must not be null");
        ElderProfile elder = repository.findById(elderId)
                .orElseThrow(() -> new IllegalStateException("Elder not found: " + elderId));
        elder.pause();
        repository.save(elder);
        cacheService.evict(CACHE_ELDER, String.valueOf(elderId));
        publishEvents(elder);
    }

    @Override
    @Transactional
    public void activateElder(Long elderId) {
        Objects.requireNonNull(elderId, "elderId must not be null");
        ElderProfile elder = repository.findById(elderId)
                .orElseThrow(() -> new IllegalStateException("Elder not found: " + elderId));
        elder.activate();
        repository.save(elder);
        cacheService.evict(CACHE_ELDER, String.valueOf(elderId));
        publishEvents(elder);
    }

    @Override
    @Transactional
    public void dischargeElder(Long elderId) {
        Objects.requireNonNull(elderId, "elderId must not be null");
        ElderProfile elder = repository.findById(elderId)
                .orElseThrow(() -> new IllegalStateException("Elder not found: " + elderId));
        elder.discharge();
        repository.save(elder);
        cacheService.evict(CACHE_ELDER, String.valueOf(elderId));
        publishEvents(elder);
    }

    public ElderProfile createElderBlockHandler(CreateElderCmd cmd, com.alibaba.csp.sentinel.slots.block.BlockException ex) {
        throw new IllegalStateException("Elder creation rate-limited: " + ex.getClass().getSimpleName());
    }

    public ElderProfile createElderFallback(CreateElderCmd cmd, Throwable t) {
        throw new IllegalStateException("Elder creation unavailable: " + t.getMessage());
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private void publishEvents(ElderProfile elder) {
        List<DomainEvent> events = elder.pullEvents();
        for (DomainEvent event : events) {
            log.info("D-148: domain event recorded: {}", event);
            eventPublisher.publish(event);
        }
    }
}
