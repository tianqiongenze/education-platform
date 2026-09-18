package com.ep.knowledge.tenant.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ep.knowledge.tenant.event.DomainEvent;
import com.ep.knowledge.tenant.event.TenantActivatedEvent;
import com.ep.knowledge.tenant.event.TenantReactivatedEvent;
import com.ep.knowledge.tenant.event.TenantSuspendedEvent;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@TableName("tenant")
public class Tenant {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    @NotBlank
    private String name;

    /** FREE | PRO | ENTERPRISE */
    private String plan;

    private Long quota;

    @TableField("brand_config")
    private String brandConfig;

    private String domain;

    /** D-201: tenant lifecycle status — PENDING | ACTIVE | SUSPENDED. */
    private String status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * D-201: domain events recorded by aggregate behavior methods. Transient
     * so MyBatis-Plus ignores it; the application service pulls them after a
     * successful transaction via {@link #pullEvents()} and publishes to the
     * event bus.
     */
    private transient final List<DomainEvent> events = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }
    public Long getQuota() { return quota; }
    public void setQuota(Long quota) { this.quota = quota; }
    public String getBrandConfig() { return brandConfig; }
    public void setBrandConfig(String brandConfig) { this.brandConfig = brandConfig; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    // --------------------- Domain behavior (state machine) ---------------------

    /**
     * Returns true iff the current status may transition to {@code newStatus}
     * (compared case-insensitively against {@link TenantStatus} names).
     * <ul>
     *   <li>PENDING → ACTIVE</li>
     *   <li>ACTIVE → SUSPENDED</li>
     *   <li>SUSPENDED → ACTIVE</li>
     * </ul>
     * Same-status is not a transition.
     */
    public boolean canTransitionTo(TenantStatus newStatus) {
        if (newStatus == null) return false;
        if (this.status == null) return false;
        TenantStatus current;
        try {
            current = TenantStatus.valueOf(this.status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (newStatus == current) return false;
        return switch (current) {
            case PENDING -> newStatus == TenantStatus.ACTIVE;
            case ACTIVE -> newStatus == TenantStatus.SUSPENDED;
            case SUSPENDED -> newStatus == TenantStatus.ACTIVE;
        };
    }

    /**
     * Transition PENDING -> ACTIVE. Records a {@link TenantActivatedEvent}
     * and stamps {@code updatedAt}.
     *
     * @throws IllegalStateException if the tenant is not in PENDING status.
     */
    public void activate() {
        if (status == null || !"PENDING".equalsIgnoreCase(status)) {
            throw new IllegalStateException(
                    "Tenant not in PENDING status, cannot activate. tenantId=" + id
                            + " status=" + status);
        }
        this.status = TenantStatus.ACTIVE.code();
        this.updatedAt = LocalDateTime.now();
        recordEvent(new TenantActivatedEvent(
                id == null ? "" : id,
                id,
                plan,
                Instant.now()));
    }

    /**
     * Transition ACTIVE -> SUSPENDED. Records a {@link TenantSuspendedEvent}
     * and stamps {@code updatedAt}.
     *
     * @throws IllegalStateException if the tenant is not in ACTIVE status.
     */
    public void suspend() {
        if (status == null || !"ACTIVE".equalsIgnoreCase(status)) {
            throw new IllegalStateException(
                    "Tenant not in ACTIVE status, cannot suspend. tenantId=" + id
                            + " status=" + status);
        }
        this.status = TenantStatus.SUSPENDED.code();
        this.updatedAt = LocalDateTime.now();
        recordEvent(new TenantSuspendedEvent(
                id == null ? "" : id,
                id,
                plan,
                Instant.now()));
    }

    /**
     * Transition SUSPENDED -> ACTIVE. Records a {@link TenantReactivatedEvent}
     * and stamps {@code updatedAt}.
     *
     * @throws IllegalStateException if the tenant is not in SUSPENDED status.
     */
    public void reactivate() {
        if (status == null || !"SUSPENDED".equalsIgnoreCase(status)) {
            throw new IllegalStateException(
                    "Tenant not in SUSPENDED status, cannot reactivate. tenantId=" + id
                            + " status=" + status);
        }
        this.status = TenantStatus.ACTIVE.code();
        this.updatedAt = LocalDateTime.now();
        recordEvent(new TenantReactivatedEvent(
                id == null ? "" : id,
                id,
                plan,
                Instant.now()));
    }

    /**
     * Pull and clear recorded domain events. The application service calls
     * this after the transaction commits so the events can be published
     * (post-commit) to the event bus.
     */
    public List<DomainEvent> pullEvents() {
        List<DomainEvent> snapshot = new ArrayList<>(events);
        events.clear();
        return snapshot;
    }

    private void recordEvent(DomainEvent event) {
        events.add(event);
    }
}
