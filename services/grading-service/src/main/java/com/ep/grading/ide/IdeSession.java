package com.ep.grading.ide;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * D-186: IdeSession aggregate root.
 * <p>
 * Tracks a collaborative IDE session backed by a Yjs CRDT document. The
 * session coordinates the server-side state for the WebContainer-powered
 * frontend editor: which room is active, who owns it, and when it should be
 * suspended for idleness.
 * <p>
 * Lifecycle:
 * <pre>
 *   CREATING → ACTIVE → SUSPENDED → ACTIVE (resume)
 *                      → IDLE → CLOSED
 *   ACTIVE → CLOSED (explicit)
 * </pre>
 * <p>
 * ID is the Yjs room id (a Snowflake-generated string stored on the
 * {@code session_id} column). The application assigns it before persist,
 * consistent with other grading-service aggregates (see {@code GradePipeline}).
 */
@Entity
@Table(name = "ide_session")
public class IdeSession implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Default idle timeout (minutes) before an ACTIVE session flips to IDLE. */
    public static final int DEFAULT_IDLE_TIMEOUT_MIN = 30;

    public enum Mode { TEACHING, JUDGE }

    public enum Status { CREATING, ACTIVE, SUSPENDED, IDLE, CLOSED }

    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false)
    private Mode mode;

    /** Yjs document id — usually equal to sessionId but kept separate for flexibility. */
    @Column(name = "yjs_doc_id", nullable = false, length = 64)
    private String yjsDocId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.CREATING;

    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "lesson_id")
    private Long lessonId;

    @Column(name = "idle_timeout_min", nullable = false)
    private Integer idleTimeoutMin = DEFAULT_IDLE_TIMEOUT_MIN;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Version
    private Integer version;

    public IdeSession() {
    }

    public IdeSession(String sessionId, Long tenantId, Long userId, Mode mode,
                      String yjsDocId, Long courseId, Long lessonId,
                      Integer idleTimeoutMin, LocalDateTime createdAt) {
        this.sessionId = sessionId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.mode = mode;
        this.yjsDocId = yjsDocId;
        this.courseId = courseId;
        this.lessonId = lessonId;
        this.idleTimeoutMin = idleTimeoutMin != null && idleTimeoutMin > 0
                ? idleTimeoutMin : DEFAULT_IDLE_TIMEOUT_MIN;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.lastActiveAt = createdAt;
        this.status = Status.CREATING;
    }

    // --- State machine ------------------------------------------------------

    /**
     * Allowed transitions:
     * CREATING→ACTIVE, ACTIVE→SUSPENDED, SUSPENDED→ACTIVE,
     * ACTIVE→IDLE, IDLE→ACTIVE, ACTIVE→CLOSED, IDLE→CLOSED,
     * SUSPENDED→CLOSED.
     */
    public boolean canTransitionTo(Status next) {
        if (next == null || this.status == next) {
            return false;
        }
        return switch (this.status) {
            case CREATING -> next == Status.ACTIVE || next == Status.CLOSED;
            case ACTIVE -> next == Status.SUSPENDED || next == Status.IDLE || next == Status.CLOSED;
            case SUSPENDED -> next == Status.ACTIVE || next == Status.CLOSED;
            case IDLE -> next == Status.ACTIVE || next == Status.CLOSED;
            case CLOSED -> false;
        };
    }

    private void transition(Status next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Illegal IDE session transition: " + status + " → " + next);
        }
        this.status = next;
        this.updatedAt = LocalDateTime.now();
        if (next == Status.CLOSED) {
            this.closedAt = this.updatedAt;
        }
    }

    /** Mark the session ACTIVE (from CREATING, SUSPENDED, or IDLE). */
    public void activate() {
        transition(Status.ACTIVE);
        this.lastActiveAt = LocalDateTime.now();
    }

    /** Suspend the session (teacher paused, waiting for students, etc.). */
    public void suspend() {
        transition(Status.SUSPENDED);
    }

    /** Flip to IDLE — called by {@link #checkIdle()} when the timeout elapses. */
    public void markIdle() {
        transition(Status.IDLE);
    }

    /** Close the session permanently. */
    public void close() {
        transition(Status.CLOSED);
    }

    /**
     * Refresh {@code lastActiveAt} to now. Called on every Yjs update so the
     * idle detector sees recent activity.
     */
    public void touch() {
        this.lastActiveAt = LocalDateTime.now();
        this.updatedAt = this.lastActiveAt;
    }

    /**
     * If the session is ACTIVE and the idle timeout has elapsed since
     * {@code lastActiveAt}, transition to IDLE and return {@code true}.
     */
    public boolean checkIdle() {
        if (status != Status.ACTIVE || lastActiveAt == null) {
            return false;
        }
        long elapsedMin = Duration.between(lastActiveAt, LocalDateTime.now()).toMinutes();
        if (elapsedMin >= idleTimeoutMin) {
            markIdle();
            return true;
        }
        return false;
    }

    // --- Getters / Setters --------------------------------------------------

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getYjsDocId() { return yjsDocId; }
    public void setYjsDocId(String yjsDocId) { this.yjsDocId = yjsDocId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public Long getLessonId() { return lessonId; }
    public void setLessonId(Long lessonId) { this.lessonId = lessonId; }
    public Integer getIdleTimeoutMin() { return idleTimeoutMin; }
    public void setIdleTimeoutMin(Integer idleTimeoutMin) { this.idleTimeoutMin = idleTimeoutMin; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(LocalDateTime lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdeSession that)) return false;
        return Objects.equals(sessionId, that.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId);
    }
}
