package com.ep.grading.grading.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "ep_grade_stage")
public class GradeStage implements Serializable {
    private static final long serialVersionUID = 1L;

    // D-132: TiDB write hotspot fix — IDENTITY (monotonic) → application-assigned Snowflake ID.
    // PingCAP best practice: avoid AUTO_INCREMENT on TiDB to prevent single-Region write hotspot.
    // ID is assigned by the service layer before persist (see GradingServiceImpl — stages are
    // cascade-saved with the parent pipeline and each gets a Snowflake id). H2 ddl-auto=create-drop
    // generates the column as bigint, compatible with both H2 (dev-local) and TiDB (prod).
    @Id
    private Long id;

    @Column(name = "stage_name", nullable = false, length = 64)
    private String stageName;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private GradeStageStatus status;

    @Column(name = "stage_score")
    private Integer stageScore;

    @Column(name = "stage_result", length = 1024)
    private String stageResult;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipeline_id")
    @JsonBackReference
    private GradePipeline pipeline;

    public GradeStage() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStageName() { return stageName; }
    public void setStageName(String stageName) { this.stageName = stageName; }
    public GradeStageStatus getStatus() { return status; }
    public void setStatus(GradeStageStatus status) { this.status = status; }
    public Integer getStageScore() { return stageScore; }
    public void setStageScore(Integer stageScore) { this.stageScore = stageScore; }
    public String getStageResult() { return stageResult; }
    public void setStageResult(String stageResult) { this.stageResult = stageResult; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public GradePipeline getPipeline() { return pipeline; }
    public void setPipeline(GradePipeline pipeline) { this.pipeline = pipeline; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GradeStage that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
