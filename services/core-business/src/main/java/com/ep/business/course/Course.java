package com.ep.business.course;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.ep.business.common.DomainEvent;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "ep_course")
public class Course implements Serializable {

    private static final long serialVersionUID = 1L;

    // D-132: TiDB write hotspot fix — IDENTITY (monotonic) → application-assigned Snowflake ID.
    // PingCAP best practice: avoid AUTO_INCREMENT on TiDB to prevent single-Region write hotspot.
    // ID is assigned by the service layer before persist (see CourseServiceImpl). H2 ddl-auto=create-drop
    // generates the column as bigint, compatible with both H2 (dev-local) and TiDB (prod).
    @Id
    @Column(name = "course_id")
    private Long courseId;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(length = 1024)
    private String description;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    @Column(nullable = false)
    private Integer category;

    @Column(nullable = false)
    private Integer difficulty;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer status;

    @Column(name = "student_count", nullable = false)
    private Integer studentCount;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    /**
     * D-066: domain events recorded by aggregate behavior methods. Transient
     * (not persisted): the application service pulls them after a successful
     * transaction via {@link #pullEvents()} and publishes to the event bus.
     */
    private transient final List<DomainEvent> events = new ArrayList<>();

    public Course() {
    }

    public Course(Long courseId, String title, String description, Long teacherId, Integer category,
                  Integer difficulty, BigDecimal price, Integer status, Integer studentCount,
                  Long createdAt, Long updatedAt) {
        this.courseId = courseId;
        this.title = title;
        this.description = description;
        this.teacherId = teacherId;
        this.category = category;
        this.difficulty = difficulty;
        this.price = price;
        this.status = status;
        this.studentCount = studentCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }
    public Integer getCategory() { return category; }
    public void setCategory(Integer category) { this.category = category; }
    public Integer getDifficulty() { return difficulty; }
    public void setDifficulty(Integer difficulty) { this.difficulty = difficulty; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getStudentCount() { return studentCount; }
    public void setStudentCount(Integer studentCount) { this.studentCount = studentCount; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getLockVersion() { return lockVersion; }
    public void setLockVersion(Long lockVersion) { this.lockVersion = lockVersion; }

    // --------------------- Domain behavior (state machine) ---------------------

    /**
     * Transition DRAFT -> PUBLISHED. Records a {@link CoursePublishedEvent}.
     *
     * @throws IllegalStateException if the course is not in DRAFT status.
     */
    public void publish() {
        if (getStatus() == null || getStatus() != CourseStatus.DRAFT.getCode()) {
            throw new IllegalStateException(
                    "Course not in DRAFT status, cannot publish. courseId=" + courseId
                            + " status=" + status);
        }
        this.status = CourseStatus.PUBLISHED.getCode();
        this.updatedAt = System.currentTimeMillis();
        recordEvent(new CoursePublishedEvent(
                courseId == null ? 0L : courseId,
                courseId == null ? 0L : courseId,
                teacherId, Instant.now()));
    }

    /**
     * Transition PUBLISHED -> ARCHIVED. Records a {@link CourseArchivedEvent}.
     *
     * @throws IllegalStateException if the course is not in PUBLISHED status.
     */
    public void archive() {
        if (getStatus() == null || getStatus() != CourseStatus.PUBLISHED.getCode()) {
            throw new IllegalStateException(
                    "Course not in PUBLISHED status, cannot archive. courseId=" + courseId
                            + " status=" + status);
        }
        this.status = CourseStatus.ARCHIVED.getCode();
        this.updatedAt = System.currentTimeMillis();
        recordEvent(new CourseArchivedEvent(
                courseId == null ? 0L : courseId,
                courseId == null ? 0L : courseId,
                teacherId, Instant.now()));
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
        if (!(o instanceof Course c)) return false;
        return Objects.equals(courseId, c.courseId);
    }

    @Override
    public int hashCode() { return Objects.hash(courseId); }
}
