package com.ep.grading.homework.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.ep.grading.common.DomainEvent;
import com.ep.grading.homework.event.AssignmentClosedEvent;
import com.ep.grading.homework.event.AssignmentPublishedEvent;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "ep_assignment")
public class Assignment implements Serializable {
    private static final long serialVersionUID = 1L;

    // D-132: TiDB write hotspot fix — IDENTITY (monotonic) → application-assigned Snowflake ID.
    // PingCAP best practice: avoid AUTO_INCREMENT on TiDB to prevent single-Region write hotspot.
    // ID is assigned by the service layer before persist (see AssignmentServiceImpl). H2 ddl-auto=create-drop
    // generates the column as bigint, compatible with both H2 (dev-local) and TiDB (prod).
    @Id
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 4096)
    private String description;

    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "teacher_id")
    private Long teacherId;

    @Column(name = "max_score")
    private Integer maxScore = 100;

    private LocalDateTime deadline;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AssignmentStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Integer version;

    /**
     * D-201: domain events recorded by aggregate behavior methods. Transient
     * (not persisted): the application service pulls them after a successful
     * transaction via {@link #pullEvents()} and publishes to the event bus.
     */
    private transient final List<DomainEvent> events = new ArrayList<>();

    public Assignment() {
    }

    public Assignment(Long id, String title, String description, Long courseId, Long teacherId,
                      Integer maxScore, LocalDateTime deadline, AssignmentStatus status,
                      LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.courseId = courseId;
        this.teacherId = teacherId;
        this.maxScore = maxScore == null ? 100 : maxScore;
        this.deadline = deadline;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
    public Integer getMaxScore() { return maxScore; }
    public void setMaxScore(Integer maxScore) { this.maxScore = maxScore; }
    public LocalDateTime getDeadline() { return deadline; }
    public void setDeadline(LocalDateTime deadline) { this.deadline = deadline; }
    public AssignmentStatus getStatus() { return status; }
    public void setStatus(AssignmentStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    // --------------------- Domain behavior (state machine) ---------------------

    /**
     * Returns true iff the current status may transition to {@code newStatus}.
     * <ul>
     *   <li>DRAFT → PUBLISHED</li>
     *   <li>PUBLISHED → CLOSED</li>
     *   <li>CLOSED → ARCHIVED</li>
     * </ul>
     * Same-status is not a transition. ARCHIVED is terminal.
     */
    public boolean canTransitionTo(AssignmentStatus newStatus) {
        if (newStatus == this.status) return false;
        return switch (this.status) {
            case DRAFT -> newStatus == AssignmentStatus.PUBLISHED;
            case PUBLISHED -> newStatus == AssignmentStatus.CLOSED;
            case CLOSED -> newStatus == AssignmentStatus.ARCHIVED;
            case ARCHIVED -> false; // terminal
        };
    }

    /**
     * Transition DRAFT -> PUBLISHED. Validates the transition, records an
     * {@link AssignmentPublishedEvent}, and stamps {@code updatedAt}.
     *
     * @throws IllegalStateException if the assignment is not in DRAFT status.
     */
    public void publish() {
        if (status != AssignmentStatus.DRAFT) {
            throw new IllegalStateException(
                    "Assignment not in DRAFT status, cannot publish. assignmentId=" + id
                            + " status=" + status);
        }
        this.status = AssignmentStatus.PUBLISHED;
        this.updatedAt = LocalDateTime.now();
        recordEvent(new AssignmentPublishedEvent(
                id == null ? 0L : id,
                id,
                courseId,
                teacherId,
                Instant.now()));
    }

    /**
     * Transition PUBLISHED -> CLOSED. Validates the transition, records an
     * {@link AssignmentClosedEvent}, and stamps {@code updatedAt}.
     *
     * @throws IllegalStateException if the assignment is not in PUBLISHED status.
     */
    public void close() {
        if (status != AssignmentStatus.PUBLISHED) {
            throw new IllegalStateException(
                    "Assignment not in PUBLISHED status, cannot close. assignmentId=" + id
                            + " status=" + status);
        }
        this.status = AssignmentStatus.CLOSED;
        this.updatedAt = LocalDateTime.now();
        recordEvent(new AssignmentClosedEvent(
                id == null ? 0L : id,
                id,
                courseId,
                teacherId,
                Instant.now()));
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
        if (!(o instanceof Assignment that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
