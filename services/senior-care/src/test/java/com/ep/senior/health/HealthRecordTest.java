package com.ep.senior.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-149: HealthRecord isAbnormal() threshold tests.
 */
class HealthRecordTest {

    private HealthRecord record() {
        HealthRecord r = new HealthRecord();
        r.setRecordId(1L);
        r.setElderId(100L);
        r.setFacilityId(10L);
        r.setTenantId(1L);
        r.setRecordedAt(System.currentTimeMillis());
        r.setCreatedAt(System.currentTimeMillis());
        return r;
    }

    @Test
    @DisplayName("normal vitals → isAbnormal=false")
    void normalVitals_notAbnormal() {
        HealthRecord r = record();
        r.setSystolicBp(120);
        r.setDiastolicBp(80);
        r.setHeartRate(72);
        r.setBloodSugar(5.5);
        r.setTemperature(36.5);
        r.setSpo2(98);
        assertFalse(r.isAbnormal());
    }

    @Test
    @DisplayName("high systolic BP > 180 → abnormal")
    void highSystolic_abnormal() {
        HealthRecord r = record();
        r.setSystolicBp(190);
        assertTrue(r.isAbnormal());
    }

    @Test
    @DisplayName("low systolic BP < 90 → abnormal")
    void lowSystolic_abnormal() {
        HealthRecord r = record();
        r.setSystolicBp(85);
        assertTrue(r.isAbnormal());
    }

    @Test
    @DisplayName("high heart rate > 120 → abnormal")
    void highHeartRate_abnormal() {
        HealthRecord r = record();
        r.setHeartRate(130);
        assertTrue(r.isAbnormal());
    }

    @Test
    @DisplayName("low heart rate < 40 → abnormal")
    void lowHeartRate_abnormal() {
        HealthRecord r = record();
        r.setHeartRate(35);
        assertTrue(r.isAbnormal());
    }

    @Test
    @DisplayName("high blood sugar > 11.1 → abnormal")
    void highBloodSugar_abnormal() {
        HealthRecord r = record();
        r.setBloodSugar(12.0);
        assertTrue(r.isAbnormal());
    }

    @Test
    @DisplayName("low blood sugar < 3.0 → abnormal")
    void lowBloodSugar_abnormal() {
        HealthRecord r = record();
        r.setBloodSugar(2.5);
        assertTrue(r.isAbnormal());
    }

    @Test
    @DisplayName("high temperature > 38.5 → abnormal")
    void highTemp_abnormal() {
        HealthRecord r = record();
        r.setTemperature(39.0);
        assertTrue(r.isAbnormal());
    }

    @Test
    @DisplayName("low temperature < 35.0 → abnormal")
    void lowTemp_abnormal() {
        HealthRecord r = record();
        r.setTemperature(34.5);
        assertTrue(r.isAbnormal());
    }

    @Test
    @DisplayName("low SpO2 < 90 → abnormal")
    void lowSpo2_abnormal() {
        HealthRecord r = record();
        r.setSpo2(88);
        assertTrue(r.isAbnormal());
    }

    @Test
    @DisplayName("all null vitals → not abnormal")
    void allNull_notAbnormal() {
        HealthRecord r = record();
        assertFalse(r.isAbnormal());
    }

    @Test
    @DisplayName("evaluateAbnormal sets flag and records event when abnormal")
    void evaluateAbnormal_recordsEvent() {
        HealthRecord r = record();
        r.setSystolicBp(200);
        r.evaluateAbnormal();
        assertTrue(r.getIsAbnormal());
        assertEquals(1, r.pullEvents().size());
    }

    @Test
    @DisplayName("evaluateAbnormal does not record event when normal")
    void evaluateNormal_noEvent() {
        HealthRecord r = record();
        r.setSystolicBp(120);
        r.evaluateAbnormal();
        assertFalse(r.getIsAbnormal());
        assertEquals(0, r.pullEvents().size());
    }
}
