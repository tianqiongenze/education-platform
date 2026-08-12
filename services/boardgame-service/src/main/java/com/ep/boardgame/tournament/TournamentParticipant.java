package com.ep.boardgame.tournament;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.util.Objects;

/**
 * D-192: A participant in a tournament.
 * <p>
 * Tracks score (1 win = 1.0, draw = 0.5, loss = 0.0), tiebreak, color preference,
 * and the JSON list of opponent playerIds already faced — used by {@link SwissPairing}
 * to avoid rematches.
 */
@Entity
@Table(name = "bg_tournament_participant")
@IdClass(TournamentParticipant.PK.class)
public class TournamentParticipant implements Serializable {

    public enum ColorPreference { BALANCE_BLACK, BALANCE_WHITE }

    /** Composite primary key. */
    public static class PK implements Serializable {
        private Long tournamentId;
        private Long playerId;

        public PK() {}
        public PK(Long tournamentId, Long playerId) {
            this.tournamentId = tournamentId;
            this.playerId = playerId;
        }
        public Long getTournamentId() { return tournamentId; }
        public void setTournamentId(Long tournamentId) { this.tournamentId = tournamentId; }
        public Long getPlayerId() { return playerId; }
        public void setPlayerId(Long playerId) { this.playerId = playerId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(tournamentId, pk.tournamentId)
                    && Objects.equals(playerId, pk.playerId);
        }
        @Override
        public int hashCode() { return Objects.hash(tournamentId, playerId); }
    }

    @Id
    @Column(name = "tournament_id")
    private Long tournamentId;

    @Id
    @Column(name = "player_id")
    private Long playerId;

    @Column(name = "seed_rating", nullable = false)
    private Integer seedRating;

    @Column(name = "score", nullable = false)
    private Double score = 0.0d;

    @Column(name = "tiebreak_score", nullable = false)
    private Double tiebreakScore = 0.0d;

    @Enumerated(EnumType.STRING)
    @Column(name = "color_preference")
    private ColorPreference colorPreference;

    /** JSON array string of opponent playerIds faced so far, e.g. "[101,205]". */
    @Column(name = "opponent_history", nullable = false)
    private String opponentHistory = "[]";

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    /** Record a result and update the score. result in {"WIN","DRAW","LOSS","BYE"}. */
    public void recordResult(String result) {
        double delta = switch (result) {
            case "WIN", "BYE" -> 1.0d;
            case "DRAW" -> 0.5d;
            case "LOSS" -> 0.0d;
            default -> throw new IllegalArgumentException("Unknown result: " + result);
        };
        this.score += delta;
    }

    /** Add a player to opponent history JSON (simple append; not validated JSON-strict here). */
    public void addOpponent(Long opponentId) {
        String trimmed = opponentHistory == null ? "[]" : opponentHistory.trim();
        String inner = trimmed.startsWith("[") ? trimmed.substring(1, trimmed.length() - 1) : "";
        String[] parts = inner.isBlank() ? new String[0] : inner.split(",");
        for (String p : parts) {
            if (p.trim().equals(String.valueOf(opponentId))) return; // already recorded
        }
        String joined = inner.isBlank() ? String.valueOf(opponentId) : inner + "," + opponentId;
        this.opponentHistory = "[" + joined + "]";
    }

    public boolean hasFaced(Long opponentId) {
        if (opponentHistory == null || opponentHistory.length() <= 2) return false;
        String inner = opponentHistory.substring(1, opponentHistory.length() - 1);
        if (inner.isBlank()) return false;
        for (String p : inner.split(",")) {
            if (p.trim().equals(String.valueOf(opponentId))) return true;
        }
        return false;
    }

    // --- Getters/Setters ---

    public Long getTournamentId() { return tournamentId; }
    public void setTournamentId(Long tournamentId) { this.tournamentId = tournamentId; }
    public Long getPlayerId() { return playerId; }
    public void setPlayerId(Long playerId) { this.playerId = playerId; }
    public Integer getSeedRating() { return seedRating; }
    public void setSeedRating(Integer seedRating) { this.seedRating = seedRating; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public Double getTiebreakScore() { return tiebreakScore; }
    public void setTiebreakScore(Double tiebreakScore) { this.tiebreakScore = tiebreakScore; }
    public ColorPreference getColorPreference() { return colorPreference; }
    public void setColorPreference(ColorPreference colorPreference) { this.colorPreference = colorPreference; }
    public String getOpponentHistory() { return opponentHistory; }
    public void setOpponentHistory(String opponentHistory) { this.opponentHistory = opponentHistory; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Long getLockVersion() { return lockVersion; }
    public void setLockVersion(Long lockVersion) { this.lockVersion = lockVersion; }
}
