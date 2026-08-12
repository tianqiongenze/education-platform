package com.ep.senior.care;

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
 * D-150: CarePlan aggregate — 照护计划.
 *
 * <p>State machine: DRAFT → ACTIVE → COMPLETED | ARCHIVED.
 * <ul>
 *   <li>{@link #activate()} — DRAFT → ACTIVE.</li>
 *   <li>{@link #complete()} — ACTIVE → COMPLETED.</li>
 *   <li>{@link #archive()} — COMPLETED → ARCHIVED.</li>
 * </ul>
 */
@Entity
@Table(name = "ep_care_plan")
public class CarePlan implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "elder_id", nullable = false)
    private Long elderId;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "assigned_caregiver_id")
    private Long assignedCaregiverId;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(length = 2048)
    private String description;

    @Column(name = "care_items", length = 4096)
    private String careItems; // JSON array of care task descriptions

    @Column(name = "start_date")
    private Long startDate;

    @Column(name = "end_date")
    private Long endDate;

    @Column(nullable = false)
    private Integer status;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    private transient final List<DomainEvent> events = new ArrayList<>();

    public CarePlan() {
    }

    // --------------------- Domain behavior (state machine) ---------------------

    /**
     * Activate the care plan. DRAFT → ACTIVE.
     * @throws IllegalStateException if not in DRAFT status.
     */
    public void activate() {
        if (getStatus() == null || getStatus() != CarePlanStatus.DRAFT.getCode()) {
            throw new IllegalStateException(
                    "CarePlan not in DRAFT, cannot activate. planId=" + planId + " status=" + status);
        }
        this.status = CarePlanStatus.ACTIVE.getCode();
        this.updatedAt = System.currentTimeMillis();
        recordEvent(new CarePlanActivatedEvent(
                planId == null ? 0L : planId,
                planId == null ? 0L : planId,
                elderId, Instant.now()));
    }

    /**
     * Complete the care plan. ACTIVE → COMPLETED.
     * @throws IllegalStateException if not in ACTIVE status.
     */
    public void complete() {
        if (getStatus() == null || getStatus() != CarePlanStatus.ACTIVE.getCode()) {
            throw new IllegalStateException(
                    "CarePlan not ACTIVE, cannot complete. planId=" + planId + " status=" + status);
        }
        this.status = CarePlanStatus.COMPLETED.getCode();
        this.updatedAt = System.currentTimeMillis();
        recordEvent(new CarePlanCompletedEvent(
                planId == null ? 0L : planId,
                planId == null ? 0L : planId,
                elderId, Instant.now()));
    }

    /**
     * Archive the care plan. COMPLETED → ARCHIVED.
     * @throws IllegalStateException if not in COMPLETED status.
     */
    public void archive() {
        if (getStatus() == null || getStatus() != CarePlanStatus.COMPLETED.getCode()) {
            throw new IllegalStateException(
                    "CarePlan not COMPLETED, cannot archive. planId=" + planId + " status=" + status);
        }
        this.status = CarePlanStatus.ARCHIVED.getCode();
        this.updatedAt = System.currentTimeMillis();
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

    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public Long getElderId() { return elderId; }
    public void setElderId(Long elderId) { this.elderId = elderId; }
    public Long getFacilityId() { return facilityId; }
    public void setFacilityId(Long facilityId) { this.facilityId = facilityId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getAssignedCaregiverId() { return assignedCaregiverId; }
    public void setAssignedCaregiverId(Long assignedCaregiverId) { this.assignedCaregiverId = assignedCaregiverId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCareItems() { return careItems; }
    public void setCareItems(String careItems) { this.careItems = careItems; }
    public Long getStartDate() { return startDate; }
    public void setStartDate(Long startDate) { this.startDate = startDate; }
    public Long getEndDate() { return endDate; }
    public void setEndDate(Long endDate) { this.endDate = endDate; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getLockVersion() { return lockVersion; }
    public void setLockVersion(Long lockVersion) { this.lockVersion = lockVersion; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CarePlan c)) return false;
        return Objects.equals(planId, c.planId);
    }

    @Override
    public int hashCode() { return Objects.hash(planId); }
}
