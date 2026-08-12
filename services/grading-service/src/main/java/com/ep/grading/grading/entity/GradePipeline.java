package com.ep.grading.grading.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "ep_grade_pipeline")
public class GradePipeline implements Serializable {
    private static final long serialVersionUID = 1L;

    // D-132: TiDB write hotspot fix — IDENTITY (monotonic) → application-assigned Snowflake ID.
    // PingCAP best practice: avoid AUTO_INCREMENT on TiDB to prevent single-Region write hotspot.
    // ID is assigned by the service layer before persist (see GradingServiceImpl). H2 ddl-auto=create-drop
    // generates the column as bigint, compatible with both H2 (dev-local) and TiDB (prod).
    @Id
    private Long id;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private GradePipelineStatus status;

    @OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id ASC")
    @JsonManagedReference
    private List<GradeStage> stages = new ArrayList<>();

    @Column(name = "total_score")
    private Integer totalScore;

    @Column(name = "graded_by")
    private Long gradedBy;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "idempotency_key", unique = true, length = 64)
    private String idempotencyKey;

    @Version
    private Integer version;

    public GradePipeline() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSubmissionId() { return submissionId; }
    public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
    public GradePipelineStatus getStatus() { return status; }
    public void setStatus(GradePipelineStatus status) { this.status = status; }
    public List<GradeStage> getStages() { return stages; }
    public void setStages(List<GradeStage> stages) { this.stages = stages; }
    public Integer getTotalScore() { return totalScore; }
    public void setTotalScore(Integer totalScore) { this.totalScore = totalScore; }
    public Long getGradedBy() { return gradedBy; }
    public void setGradedBy(Long gradedBy) { this.gradedBy = gradedBy; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GradePipeline that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
