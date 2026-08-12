package com.ep.senior.care;

import com.ep.cache.CacheService;
import com.ep.senior.event.PulsarClientProperties;
import com.ep.senior.event.PulsarEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * D-150: CarePlanServiceImpl unit tests.
 * D-182: verifies CacheService getOrLoad/evict integration.
 */
class CarePlanServiceImplTest {

    private CarePlanRepository repository;
    private CacheService cacheService;
    private CarePlanServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(CarePlanRepository.class);
        when(repository.save(any(CarePlan.class))).thenAnswer(inv -> {
            CarePlan p = inv.getArgument(0);
            if (p.getPlanId() == null) p.setPlanId(System.nanoTime() % 100000L);
            return p;
        });
        cacheService = mock(CacheService.class);
        // Cache miss: invoke the loader so the real repository is exercised.
        when(cacheService.getOrLoad(anyString(), anyString(), any(), anyLong(), anyBoolean()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> loader = inv.getArgument(2);
                    return loader.get();
                });
        service = new CarePlanServiceImpl(repository, noopPublisher(), cacheService);
    }

    private static PulsarEventPublisher noopPublisher() {
        ObjectProvider<org.apache.pulsar.client.api.PulsarClient> cp = mock(ObjectProvider.class);
        when(cp.getIfAvailable()).thenReturn(null);
        PulsarClientProperties props = new PulsarClientProperties();
        PulsarEventPublisher pub = new PulsarEventPublisher(cp, props, false);
        pub.init();
        return pub;
    }

    private CreateCarePlanCmd cmd(String title) {
        CreateCarePlanCmd c = new CreateCarePlanCmd();
        c.setTenantId(10L);
        c.setFacilityId(100L);
        c.setElderId(1L);
        c.setTitle(title);
        return c;
    }

    @Test
    void createPlan_success() {
        CarePlan plan = service.createPlan(cmd("Morning Care"));
        assertNotNull(plan.getPlanId());
        assertEquals("Morning Care", plan.getTitle());
        assertEquals(CarePlanStatus.DRAFT.getCode(), plan.getStatus());
        // D-182: create should evict the care-plan cache entry.
        verify(cacheService).evict("senior:care-plan", String.valueOf(plan.getPlanId()));
    }

    @Test
    void createPlan_blankTitle_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.createPlan(cmd("  ")));
        verify(cacheService, never()).evict(anyString(), anyString());
    }

    @Test
    void getPlanById_found() {
        CarePlan plan = newPlan(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(plan));
        CarePlan result = service.getPlanById(1L);
        assertNotNull(result);
        assertEquals(1L, result.getPlanId());
        // D-182: getPlanById goes through cache with cacheEmpty=true (TTL 600s).
        verify(cacheService).getOrLoad(eq("senior:care-plan"), eq("1"), any(), eq(600L), eq(true));
    }

    @Test
    void getPlanById_notFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());
        assertNull(service.getPlanById(999L));
        verify(cacheService).getOrLoad(eq("senior:care-plan"), eq("999"), any(), eq(600L), eq(true));
    }

    @Test
    void getPlanById_cacheHit_skipsRepository() {
        // Simulate a cache hit: getOrLoad returns the plan without invoking the loader.
        CarePlan cached = newPlan(1L);
        when(cacheService.getOrLoad(eq("senior:care-plan"), eq("1"), any(), eq(600L), eq(true)))
                .thenReturn(cached);
        CarePlan result = service.getPlanById(1L);
        assertNotNull(result);
        assertEquals(1L, result.getPlanId());
        // Repository must NOT be called on a cache hit.
        verify(repository, never()).findById(anyLong());
    }

    @Test
    void activatePlan_success() {
        CarePlan plan = newPlan(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(plan));
        service.activatePlan(1L);
        assertEquals(CarePlanStatus.ACTIVE.getCode(), plan.getStatus());
        // D-182: status change should evict the cache entry.
        verify(cacheService).evict("senior:care-plan", "1");
    }

    @Test
    void completePlan_success() {
        CarePlan plan = newPlan(1L);
        plan.activate(); // DRAFT -> ACTIVE
        when(repository.findById(1L)).thenReturn(Optional.of(plan));
        service.completePlan(1L);
        assertEquals(CarePlanStatus.COMPLETED.getCode(), plan.getStatus());
        verify(cacheService).evict("senior:care-plan", "1");
    }

    @Test
    void archivePlan_success() {
        CarePlan plan = newPlan(1L);
        plan.activate();   // DRAFT -> ACTIVE
        plan.complete();   // ACTIVE -> COMPLETED
        when(repository.findById(1L)).thenReturn(Optional.of(plan));
        service.archivePlan(1L);
        assertEquals(CarePlanStatus.ARCHIVED.getCode(), plan.getStatus());
        verify(cacheService).evict("senior:care-plan", "1");
    }

    @Test
    void activatePlan_notFound_throws() {
        when(repository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.activatePlan(999L));
    }

    private CarePlan newPlan(Long id) {
        CarePlan plan = new CarePlan();
        plan.setPlanId(id);
        plan.setElderId(1L);
        plan.setFacilityId(100L);
        plan.setTenantId(10L);
        plan.setTitle("test-" + id);
        plan.setStatus(CarePlanStatus.DRAFT.getCode());
        plan.setCreatedAt(System.currentTimeMillis());
        plan.setUpdatedAt(System.currentTimeMillis());
        return plan;
    }
}
