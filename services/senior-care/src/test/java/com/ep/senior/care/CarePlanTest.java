package com.ep.senior.care;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-150: CarePlan state machine unit tests.
 */
class CarePlanTest {

    private CarePlan newPlan() {
        CarePlan p = new CarePlan();
        p.setPlanId(1L);
        p.setElderId(100L);
        p.setFacilityId(10L);
        p.setTenantId(1L);
        p.setTitle("日常照护计划");
        p.setStatus(CarePlanStatus.DRAFT.getCode());
        p.setCreatedAt(System.currentTimeMillis());
        p.setUpdatedAt(System.currentTimeMillis());
        return p;
    }

    @Test
    @DisplayName("activate from DRAFT → ACTIVE, records event")
    void activate_fromDraft_succeeds() {
        CarePlan p = newPlan();
        p.activate();
        assertEquals(CarePlanStatus.ACTIVE.getCode(), p.getStatus());
        assertEquals(1, p.pullEvents().size());
    }

    @Test
    @DisplayName("activate from non-DRAFT throws")
    void activate_fromNonDraft_throws() {
        CarePlan p = newPlan();
        p.setStatus(CarePlanStatus.ACTIVE.getCode());
        assertThrows(IllegalStateException.class, p::activate);
    }

    @Test
    @DisplayName("complete from ACTIVE → COMPLETED, records event")
    void complete_fromActive_succeeds() {
        CarePlan p = newPlan();
        p.setStatus(CarePlanStatus.ACTIVE.getCode());
        p.complete();
        assertEquals(CarePlanStatus.COMPLETED.getCode(), p.getStatus());
        assertEquals(1, p.pullEvents().size());
    }

    @Test
    @DisplayName("complete from DRAFT throws")
    void complete_fromDraft_throws() {
        CarePlan p = newPlan();
        assertThrows(IllegalStateException.class, p::complete);
    }

    @Test
    @DisplayName("archive from COMPLETED → ARCHIVED, no event")
    void archive_fromCompleted_succeeds() {
        CarePlan p = newPlan();
        p.setStatus(CarePlanStatus.COMPLETED.getCode());
        p.archive();
        assertEquals(CarePlanStatus.ARCHIVED.getCode(), p.getStatus());
        assertEquals(0, p.pullEvents().size());
    }

    @Test
    @DisplayName("archive from ACTIVE throws")
    void archive_fromActive_throws() {
        CarePlan p = newPlan();
        p.setStatus(CarePlanStatus.ACTIVE.getCode());
        assertThrows(IllegalStateException.class, p::archive);
    }

    @Test
    @DisplayName("full lifecycle DRAFT → ACTIVE → COMPLETED → ARCHIVED")
    void fullLifecycle() {
        CarePlan p = newPlan();
        p.activate();
        p.complete();
        p.archive();
        assertEquals(CarePlanStatus.ARCHIVED.getCode(), p.getStatus());
        assertEquals(2, p.pullEvents().size()); // activate + complete events
    }

    @Test
    @DisplayName("pullEvents clears after pull")
    void pullEvents_clears() {
        CarePlan p = newPlan();
        p.activate();
        assertEquals(1, p.pullEvents().size());
        assertEquals(0, p.pullEvents().size());
    }

    @Test
    @DisplayName("CarePlanStatus fromCode round-trip")
    void statusFromCode() {
        for (CarePlanStatus s : CarePlanStatus.values()) {
            assertEquals(s, CarePlanStatus.fromCode(s.getCode()));
        }
        assertThrows(IllegalArgumentException.class, () -> CarePlanStatus.fromCode(99));
    }
}
