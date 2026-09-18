package com.ep.grading.homework.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "ep_plagiarism_check")
public class PlagiarismCheck implements Serializable {
    private static final long serialVersionUID = 1L;

    // D-132: TiDB write hotspot fix — IDENTITY (monotonic) → application-assigned Snowflake ID.
    // PingCAP best practice: avoid AUTO_INCREMENT on TiDB to prevent single-Region write hotspot.
    // ID is assigned by the service layer before persist (see PlagiarismServiceImpl). H2 ddl-auto=create-drop
    // generates the column as bigint, compatible with both H2 (dev-local) and TiDB (prod).
    @Id
    private Long id;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "similarity_score")
    private Double similarityScore;

    @Column(name = "matched_sources", length = 2048)
    private String matchedSources;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PlagiarismStatus status;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    public PlagiarismCheck() {
    }

    /**
     * D-224: rich aggregate behaviour — classifies a similarity score into a
     * plagiarism verdict and stamps the audit timestamp. The classification
     * thresholds (CLEAN &lt; 0.3 ≤ REVIEWING &lt; 0.7 ≤ FLAGGED) are owned by
     * the aggregate, not the service layer.
     *
     * @param similarityScore similarity in [0.0, 1.0]
     */
    public void classify(double similarityScore) {
        this.similarityScore = similarityScore;
        if (similarityScore < 0.3) {
            this.status = PlagiarismStatus.CLEAN;
        } else if (similarityScore >= 0.7) {
            this.status = PlagiarismStatus.FLAGGED;
        } else {
            this.status = PlagiarismStatus.REVIEWING;
        }
        this.checkedAt = java.time.LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSubmissionId() { return submissionId; }
    public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
    public Double getSimilarityScore() { return similarityScore; }
    public void setSimilarityScore(Double similarityScore) { this.similarityScore = similarityScore; }
    public String getMatchedSources() { return matchedSources; }
    public void setMatchedSources(String matchedSources) { this.matchedSources = matchedSources; }
    public PlagiarismStatus getStatus() { return status; }
    public void setStatus(PlagiarismStatus status) { this.status = status; }
    public LocalDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(LocalDateTime checkedAt) { this.checkedAt = checkedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlagiarismCheck that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
