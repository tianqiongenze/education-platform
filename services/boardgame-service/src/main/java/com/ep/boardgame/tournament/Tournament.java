package com.ep.boardgame.tournament;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.time.Instant;

/**
 * D-192: Tournament aggregate root.
 * <p>
 * Tracks a boardgame tournament lifecycle:
 * SCHEDULED → IN_PROGRESS → COMPLETED (or CANCELLED).
 */
@Entity
@Table(name = "bg_tournament")
public class Tournament implements Serializable {

    public enum GameType { XIANGQI, GO }
    public enum Format { SWISS, ELIMINATION, ROUND_ROBIN }
    public enum Status { SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED }

    @Id
    @Column(name = "tournament_id")
    private Long tournamentId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_type", nullable = false)
    private GameType gameType;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false)
    private Format format;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.SCHEDULED;

    @Column(name = "max_participants", nullable = false)
    private Integer maxParticipants;

    @Column(name = "current_round", nullable = false)
    private Integer currentRound = 0;

    @Column(name = "total_rounds", nullable = false)
    private Integer totalRounds;

    @Column(name = "start_date")
    private Instant startDate;

    @Column(name = "end_date")
    private Instant endDate;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    // --- Lifecycle helpers ---

    public void start() {
        if (status != Status.SCHEDULED) {
            throw new IllegalStateException("Tournament must be SCHEDULED to start, was " + status);
        }
        this.status = Status.IN_PROGRESS;
        this.currentRound = 1;
        this.updatedAt = System.currentTimeMillis();
    }

    public void advanceRound() {
        if (status != Status.IN_PROGRESS) {
            throw new IllegalStateException("Tournament is not IN_PROGRESS");
        }
        if (currentRound >= totalRounds) {
            throw new IllegalStateException("Already at final round " + currentRound);
        }
        this.currentRound++;
        this.updatedAt = System.currentTimeMillis();
    }

    public void complete() {
        if (status != Status.IN_PROGRESS) {
            throw new IllegalStateException("Tournament is not IN_PROGRESS");
        }
        this.status = Status.COMPLETED;
        this.endDate = Instant.now();
        this.updatedAt = System.currentTimeMillis();
    }

    public void cancel() {
        if (status == Status.COMPLETED || status == Status.CANCELLED) {
            throw new IllegalStateException("Tournament already " + status);
        }
        this.status = Status.CANCELLED;
        this.updatedAt = System.currentTimeMillis();
    }

    // --- Getters/Setters ---

    public Long getTournamentId() { return tournamentId; }
    public void setTournamentId(Long tournamentId) { this.tournamentId = tournamentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public GameType getGameType() { return gameType; }
    public void setGameType(GameType gameType) { this.gameType = gameType; }
    public Format getFormat() { return format; }
    public void setFormat(Format format) { this.format = format; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Integer getMaxParticipants() { return maxParticipants; }
    public void setMaxParticipants(Integer maxParticipants) { this.maxParticipants = maxParticipants; }
    public Integer getCurrentRound() { return currentRound; }
    public void setCurrentRound(Integer currentRound) { this.currentRound = currentRound; }
    public Integer getTotalRounds() { return totalRounds; }
    public void setTotalRounds(Integer totalRounds) { this.totalRounds = totalRounds; }
    public Instant getStartDate() { return startDate; }
    public void setStartDate(Instant startDate) { this.startDate = startDate; }
    public Instant getEndDate() { return endDate; }
    public void setEndDate(Instant endDate) { this.endDate = endDate; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getLockVersion() { return lockVersion; }
    public void setLockVersion(Long lockVersion) { this.lockVersion = lockVersion; }
}
