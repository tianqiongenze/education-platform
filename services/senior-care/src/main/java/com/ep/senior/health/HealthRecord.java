package com.ep.senior.health;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.ep.senior.common.DomainEvent;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * D-149: HealthRecord aggregate — 健康体征记录.
 *
 * <p>Immutable record of a single health check: blood pressure, heart rate,
 * blood sugar, temperature, SpO2. The {@link #isAbnormal()} method flags
 * readings that fall outside safe thresholds for caregiver attention.
 *
 * <p>No state machine — records are append-only. The aggregate still supports
 * optimistic locking via @Version in case of concurrent edits.
 */
@Entity
@Table(name = "ep_health_record")
public class HealthRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "elder_id", nullable = false)
    private Long elderId;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Systolic blood pressure in mmHg (e.g. 120). */
    @Column(name = "systolic_bp")
    private Integer systolicBp;

    /** Diastolic blood pressure in mmHg (e.g. 80). */
    @Column(name = "diastolic_bp")
    private Integer diastolicBp;

    /** Heart rate in bpm. */
    @Column(name = "heart_rate")
    private Integer heartRate;

    /** Blood sugar in mmol/L. */
    @Column(name = "blood_sugar")
    private Double bloodSugar;

    /** Body temperature in °C. */
    @Column(name = "temperature")
    private Double temperature;

    /** Blood oxygen saturation (%). */
    @Column(name = "spo2")
    private Integer spo2;

    @Column(length = 1024)
    private String notes;

    /** true if any vital is outside safe range (set by service or computed). */
    @Column(name = "is_abnormal", nullable = false)
    private Boolean isAbnormal;

    @Column(name = "recorded_at", nullable = false)
    private Long recordedAt;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    private transient final List<DomainEvent> events = new ArrayList<>();

    public HealthRecord() {
    }

    /**
     * Determine if any vital sign is outside safe thresholds.
     * Called by the service before persisting to set the isAbnormal flag.
     *
     * @return true if any reading is abnormal.
     */
    public boolean isAbnormal() {
        if (systolicBp != null && (systolicBp > 180 || systolicBp < 90)) return true;
        if (diastolicBp != null && (diastolicBp > 120 || diastolicBp < 60)) return true;
        if (heartRate != null && (heartRate > 120 || heartRate < 40)) return true;
        if (bloodSugar != null && (bloodSugar > 11.1 || bloodSugar < 3.0)) return true;
        if (temperature != null && (temperature > 38.5 || temperature < 35.0)) return true;
        if (spo2 != null && spo2 < 90) return true;
        return false;
    }

    /**
     * Evaluate and set the abnormal flag. Called by the service after populating vitals.
     */
    public void evaluateAbnormal() {
        this.isAbnormal = isAbnormal();
        if (Boolean.TRUE.equals(this.isAbnormal)) {
            recordEvent(new HealthAbnormalDetectedEvent(
                    recordId == null ? 0L : recordId,
                    elderId == null ? 0L : elderId,
                    Instant.now()));
        }
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> snapshot = new ArrayList<>(events);
        events.clear();
        return snapshot;
    }

    private void recordEvent(DomainEvent event) {
        events.add(event);
    }

    // --------------------- Getters / Setters ---------------------

    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public Long getElderId() { return elderId; }
    public void setElderId(Long elderId) { this.elderId = elderId; }
    public Long getFacilityId() { return facilityId; }
    public void setFacilityId(Long facilityId) { this.facilityId = facilityId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Integer getSystolicBp() { return systolicBp; }
    public void setSystolicBp(Integer systolicBp) { this.systolicBp = systolicBp; }
    public Integer getDiastolicBp() { return diastolicBp; }
    public void setDiastolicBp(Integer diastolicBp) { this.diastolicBp = diastolicBp; }
    public Integer getHeartRate() { return heartRate; }
    public void setHeartRate(Integer heartRate) { this.heartRate = heartRate; }
    public Double getBloodSugar() { return bloodSugar; }
    public void setBloodSugar(Double bloodSugar) { this.bloodSugar = bloodSugar; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getSpo2() { return spo2; }
    public void setSpo2(Integer spo2) { this.spo2 = spo2; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Boolean getIsAbnormal() { return isAbnormal; }
    public void setIsAbnormal(Boolean isAbnormal) { this.isAbnormal = isAbnormal; }
    public Long getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Long recordedAt) { this.recordedAt = recordedAt; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getLockVersion() { return lockVersion; }
    public void setLockVersion(Long lockVersion) { this.lockVersion = lockVersion; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HealthRecord h)) return false;
        return Objects.equals(recordId, h.recordId);
    }

    @Override
    public int hashCode() { return Objects.hash(recordId); }
}
