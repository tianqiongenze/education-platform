package com.ep.senior.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-148: ElderProfile state machine unit tests.
 */
class ElderProfileTest {

    private ElderProfile newElder() {
        ElderProfile e = new ElderProfile();
        e.setElderId(1L);
        e.setFacilityId(100L);
        e.setTenantId(10L);
        e.setName("张爷爷");
        e.setStatus(ElderStatus.ACTIVE.getCode());
        e.setCreatedAt(System.currentTimeMillis());
        e.setUpdatedAt(System.currentTimeMillis());
        return e;
    }

    @Test
    @DisplayName("activate from null status should succeed and record event")
    void activate_fromNull_succeeds() {
        ElderProfile e = newElder();
        e.setStatus(null);
        e.activate();
        assertEquals(ElderStatus.ACTIVE.getCode(), e.getStatus());
        assertEquals(1, e.pullEvents().size());
    }

    @Test
    @DisplayName("activate from PAUSED should succeed")
    void activate_fromPaused_succeeds() {
        ElderProfile e = newElder();
        e.setStatus(ElderStatus.PAUSED.getCode());
        e.activate();
        assertEquals(ElderStatus.ACTIVE.getCode(), e.getStatus());
        assertEquals(1, e.pullEvents().size());
    }

    @Test
    @DisplayName("activate from ACTIVE should throw IllegalStateException")
    void activate_fromActive_throws() {
        ElderProfile e = newElder(); // already ACTIVE
        assertThrows(IllegalStateException.class, e::activate);
    }

    @Test
    @DisplayName("activate from DISCHARGED should throw IllegalStateException")
    void activate_fromDischarged_throws() {
        ElderProfile e = newElder();
        e.setStatus(ElderStatus.DISCHARGED.getCode());
        assertThrows(IllegalStateException.class, e::activate);
    }

    @Test
    @DisplayName("pause from ACTIVE should succeed")
    void pause_fromActive_succeeds() {
        ElderProfile e = newElder();
        e.pause();
        assertEquals(ElderStatus.PAUSED.getCode(), e.getStatus());
    }

    @Test
    @DisplayName("pause from non-ACTIVE should throw")
    void pause_fromNonActive_throws() {
        ElderProfile e = newElder();
        e.setStatus(ElderStatus.PAUSED.getCode());
        assertThrows(IllegalStateException.class, e::pause);
    }

    @Test
    @DisplayName("discharge from ACTIVE should succeed and record event")
    void discharge_fromActive_succeeds() {
        ElderProfile e = newElder();
        e.discharge();
        assertEquals(ElderStatus.DISCHARGED.getCode(), e.getStatus());
        assertEquals(1, e.pullEvents().size());
    }

    @Test
    @DisplayName("discharge from PAUSED should succeed")
    void discharge_fromPaused_succeeds() {
        ElderProfile e = newElder();
        e.setStatus(ElderStatus.PAUSED.getCode());
        e.discharge();
        assertEquals(ElderStatus.DISCHARGED.getCode(), e.getStatus());
    }

    @Test
    @DisplayName("discharge from DISCHARGED should throw")
    void discharge_fromDischarged_throws() {
        ElderProfile e = newElder();
        e.setStatus(ElderStatus.DISCHARGED.getCode());
        assertThrows(IllegalStateException.class, e::discharge);
    }

    @Test
    @DisplayName("discharge from null status should throw")
    void discharge_fromNull_throws() {
        ElderProfile e = newElder();
        e.setStatus(null);
        assertThrows(IllegalStateException.class, e::discharge);
    }

    @Test
    @DisplayName("pullEvents should clear events after pulling")
    void pullEvents_clears() {
        ElderProfile e = newElder();
        e.setStatus(null);
        e.activate();
        assertEquals(1, e.pullEvents().size());
        assertEquals(0, e.pullEvents().size());
    }

    @Test
    @DisplayName("equals/hashCode based on elderId")
    void equalsHashCode() {
        ElderProfile e1 = newElder();
        ElderProfile e2 = newElder();
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
        e2.setElderId(2L);
        assertNotEquals(e1, e2);
    }
}
