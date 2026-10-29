package com.ep.business.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.ep.business.common.DomainEvent;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "ep_user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    // D-132: TiDB write hotspot fix — IDENTITY (monotonic) → application-assigned Snowflake ID.
    // PingCAP best practice: avoid AUTO_INCREMENT on TiDB to prevent single-Region write hotspot.
    // ID is assigned by the service layer before persist (see UserServiceImpl). H2 ddl-auto=create-drop
    // generates the column as bigint, compatible with both H2 (dev-local) and TiDB (prod).
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false, unique = true, length = 32)
    private String phone;

    @Column(length = 128)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    @Column(length = 64)
    private String nickname;

    @Column(length = 256)
    private String avatar;

    @Column(nullable = false)
    private Integer status;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    /**
     * D-242: authorization role. P1-1 角色体系落地 — the JWT {@code role}
     * claim was hardcoded to "user"; this column is its source of truth.
     * Nullable for pre-existing rows (login defensively defaults null →
     * "user"); seeded teacher/admin accounts carry their real role here.
     */
    @Column(length = 32)
    private String role;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    /**
     * D-066: domain events recorded by aggregate behavior methods. Transient
     * (not persisted): the application service pulls them after a successful
     * transaction via {@link #pullEvents()} and publishes to the event bus.
     */
    private transient final List<DomainEvent> events = new ArrayList<>();

    public User() {
    }

    public User(Long userId, String username, String phone, String email, String passwordHash,
                String nickname, String avatar, Integer status, Long tenantId,
                Long createdAt, Long updatedAt) {
        this.userId = userId;
        this.username = username;
        this.phone = phone;
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.avatar = avatar;
        this.status = status;
        this.tenantId = tenantId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }

    public String getRole() { return role; }

    public void setRole(String role) { this.role = role; }
    public Long getLockVersion() { return lockVersion; }
    public void setLockVersion(Long lockVersion) { this.lockVersion = lockVersion; }

    // --------------------- Domain behavior (state machine) ---------------------

    /**
     * Transition * -> FROZEN. Records a {@link UserFrozenEvent}.
     *
     * @param reason human-readable freeze reason.
     * @throws IllegalStateException if the user is already FROZEN.
     */
    public void freeze(String reason) {
        if (getStatus() != null && getStatus() == UserStatus.FROZEN.getCode()) {
            throw new IllegalStateException(
                    "User already FROZEN, cannot freeze again. userId=" + userId);
        }
        this.status = UserStatus.FROZEN.getCode();
        this.updatedAt = System.currentTimeMillis();
        recordEvent(new UserFrozenEvent(
                userId == null ? 0L : userId,
                userId == null ? 0L : userId,
                reason, Instant.now()));
    }

    /**
     * Transition * -> ACTIVE. Used to unfreeze a previously frozen user.
     */
    public void activate() {
        this.status = UserStatus.ACTIVE.getCode();
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * Pull and clear recorded domain events. The application service calls
     * this after the JPA transaction commits so the events can be published
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User u)) return false;
        return Objects.equals(userId, u.userId);
    }

    @Override
    public int hashCode() { return Objects.hash(userId); }
}
