package com.ep.senior.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import com.ep.senior.common.DomainEvent;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * D-148: ElderProfile aggregate root — 康养养老人员档案.
 *
 * <p>State machine: ACTIVE ↔ PAUSED → DISCHARGED.
 * <ul>
 *   <li>{@link #activate()} — sets status to ACTIVE (from new or PAUSED).</li>
 *   <li>{@link #pause()} — ACTIVE → PAUSED (temporary hold, e.g. hospital visit).</li>
 *   <li>{@link #discharge()} — ACTIVE/PAUSED → DISCHARGED (leaves facility permanently).</li>
 * </ul>
 *
 * <p>DDD gold standard: application-assigned Snowflake ID, @Version optimistic
 * locking, transient domain events pulled post-commit.
 */
@Entity
    // D-221: unique constraint applies to freshly created schemas; existing
    // databases need `ALTER TABLE ep_elder_profile ADD UNIQUE KEY uk_ep_elder_idem (tenant_id, idempotency_key)`.
    @Table(name = "ep_elder_profile",
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_ep_elder_idem",
                    columnNames = {"tenant_id", "idempotency_key"}))
public class ElderProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "elder_id")
    private Long elderId;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 16)
    private String gender;

    @Column(name = "birth_date")
    private String birthDate; // ISO yyyy-MM-dd

    @Column(name = "id_card", length = 32)
    private String idCard;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "emergency_contact", length = 128)
    private String emergencyContact;

    @Column(length = 256)
    private String address;

    @Column(length = 1024)
    private String notes;

    @Column(nullable = false)
    private Integer status;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    /**
     * D-221: caller-supplied business idempotency key for profile creation.
     * Nullable — legacy callers that omit it keep the old create-always
     * behaviour. Uniqueness is enforced by the DB unique constraint on fresh
     * schemas and probed via the repository before insert.
     */
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    private transient final List<DomainEvent> events = new ArrayList<>();

    public ElderProfile() {
    }

    // --------------------- Domain behavior (state machine) ---------------------

    /**
     * Activate the elder profile. Valid from new (null status) or PAUSED.
     * @throws IllegalStateException if already ACTIVE or DISCHARGED.
     */
    public void activate() {
        if (getStatus() != null && getStatus() == ElderStatus.ACTIVE.getCode()) {
            throw new IllegalStateException(
                    "Elder already ACTIVE, cannot activate. elderId=" + elderId);
        }
        if (getStatus() != null && getStatus() == ElderStatus.DISCHARGED.getCode()) {
            throw new IllegalStateException(
                    "Elder already DISCHARGED, cannot reactivate. elderId=" + elderId);
        }
        boolean wasPaused = getStatus() != null && getStatus() == ElderStatus.PAUSED.getCode();
        this.status = ElderStatus.ACTIVE.getCode();
        this.updatedAt = System.currentTimeMillis();
        recordEvent(new ElderActivatedEvent(
                elderId == null ? 0L : elderId,
                elderId == null ? 0L : elderId,
                facilityId, Instant.now()));
        // wasPaused is available for conditional logic if needed
        if (wasPaused) {
            // resumption from pause — event already recorded above
        }
    }

    /**
     * Pause care temporarily. Only valid from ACTIVE.
     * @throws IllegalStateException if not ACTIVE.
     */
    public void pause() {
        if (getStatus() == null || getStatus() != ElderStatus.ACTIVE.getCode()) {
            throw new IllegalStateException(
                    "Elder not ACTIVE, cannot pause. elderId=" + elderId + " status=" + status);
        }
        this.status = ElderStatus.PAUSED.getCode();
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * Discharge the elder permanently. Valid from ACTIVE or PAUSED.
     * @throws IllegalStateException if already DISCHARGED.
     */
    public void discharge() {
        if (getStatus() != null && getStatus() == ElderStatus.DISCHARGED.getCode()) {
            throw new IllegalStateException(
                    "Elder already DISCHARGED. elderId=" + elderId);
        }
        if (getStatus() == null) {
            throw new IllegalStateException(
                    "Elder not yet activated, cannot discharge. elderId=" + elderId);
        }
        this.status = ElderStatus.DISCHARGED.getCode();
        this.updatedAt = System.currentTimeMillis();
        recordEvent(new ElderDischargedEvent(
                elderId == null ? 0L : elderId,
                elderId == null ? 0L : elderId,
                facilityId, Instant.now()));
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

    public Long getElderId() { return elderId; }
    public void setElderId(Long elderId) { this.elderId = elderId; }
    public Long getFacilityId() { return facilityId; }
    public void setFacilityId(Long facilityId) { this.facilityId = facilityId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getBirthDate() { return birthDate; }
    public void setBirthDate(String birthDate) { this.birthDate = birthDate; }
    public String getIdCard() { return idCard; }
    public void setIdCard(String idCard) { this.idCard = idCard; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmergencyContact() { return emergencyContact; }
    public void setEmergencyContact(String emergencyContact) { this.emergencyContact = emergencyContact; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
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
        if (!(o instanceof ElderProfile e)) return false;
        return Objects.equals(elderId, e.elderId);
    }

    @Override
    public int hashCode() { return Objects.hash(elderId); }
}
