package com.ep.grading.homework.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "ep_submission")
public class Submission implements Serializable {
    private static final long serialVersionUID = 1L;

    // D-132: TiDB write hotspot fix — IDENTITY (monotonic) → application-assigned Snowflake ID.
    // PingCAP best practice: avoid AUTO_INCREMENT on TiDB to prevent single-Region write hotspot.
    // ID is assigned by the service layer before persist (see SubmissionServiceImpl). H2 ddl-auto=create-drop
    // generates the column as bigint, compatible with both H2 (dev-local) and TiDB (prod).
    @Id
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(length = 4096)
    private String content;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private SubmissionStatus status;

    private Integer score;

    @Column(length = 2048)
    private String feedback;

    @Column(name = "idempotency_key", unique = true, length = 64)
    private String idempotencyKey;

    @Version
    private Integer version;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "graded_at")
    private LocalDateTime gradedAt;

    public Submission() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAssignmentId() { return assignmentId; }
    public void setAssignmentId(Long assignmentId) { this.assignmentId = assignmentId; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public SubmissionStatus getStatus() { return status; }
    public void setStatus(SubmissionStatus status) { this.status = status; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public LocalDateTime getGradedAt() { return gradedAt; }
    public void setGradedAt(LocalDateTime gradedAt) { this.gradedAt = gradedAt; }

    // ------------------------------------------------------------------
    // D-224: rich aggregate behaviour. State transitions are owned by the
    // aggregate (not the service layer) — the same convention as the
    // Assignment/Tenant aggregates (D-200): guards live next to the state
    // they protect, transitions are all-or-nothing, and each transition
    // stamps the audit timestamps.
    // ------------------------------------------------------------------

    /**
     * Marks the submission as SUBMITTED (initial lifecycle transition).
     *
     * @param studentId submitter — must match the aggregate owner (IDOR guard)
     * @param content   submission content
     * @param idempotencyKey client idempotency key (nullable)
     * @throws IllegalStateException when the submission is not in its pristine
     *                               (pre-submitted) state
     */
    public void submit(Long studentId, String content, String idempotencyKey) {
        if (this.status != null) {
            throw new IllegalStateException(
                    "Submission already submitted (id=" + id + ", status=" + status + ")");
        }
        if (studentId == null || !studentId.equals(this.studentId)) {
            throw new IllegalStateException("Submitter does not own this submission");
        }
        this.content = content;
        this.status = SubmissionStatus.SUBMITTED;
        this.submittedAt = LocalDateTime.now();
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Records a grade (SUBMITTED → GRADED).
     *
     * @param score    graded score (0..maxScore enforced by the caller's rubric)
     * @param feedback teacher/AI feedback
     * @throws IllegalStateException when the submission is not SUBMITTED
     */
    public void grade(Integer score, String feedback) {
        if (this.status != SubmissionStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "Submission must be SUBMITTED to grade, current: " + status);
        }
        this.score = score;
        this.feedback = feedback;
        this.status = SubmissionStatus.GRADED;
        this.gradedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Submission that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

