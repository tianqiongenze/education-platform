package com.ep.boardgame.rating;

import com.ep.boardgame.tournament.Tournament.GameType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * D-191 (ADR-BG09): Player rating aggregate.
 * <p>
 * Tracks a player's ELO rating per game type (XIANGQI / GO). Default rating
 * is 1200. K-factor tiers are handled by {@link EloCalculator}.
 * <p>
 * ID is application-assigned (Snowflake) by the service layer before persist.
 */
@Entity
@Table(name = "bg_player_rating")
public class PlayerRating implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_RATING = 1200;

    @Id
    @Column(name = "rating_id")
    private Long ratingId;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_type", nullable = false)
    private GameType gameType;

    @Column(name = "rating", nullable = false)
    private Integer rating = DEFAULT_RATING;

    @Column(name = "rank")
    private String rank;

    @Column(name = "wins", nullable = false)
    private Integer wins = 0;

    @Column(name = "losses", nullable = false)
    private Integer losses = 0;

    @Column(name = "draws", nullable = false)
    private Integer draws = 0;

    @Column(name = "games_played", nullable = false)
    private Integer gamesPlayed = 0;

    @Column(name = "peak_rating")
    private Integer peakRating = DEFAULT_RATING;

    @Column(name = "last_game_at")
    private Instant lastGameAt;

    @Version
    private Integer version;

    public PlayerRating() {
    }

    public PlayerRating(Long ratingId, Long playerId, GameType gameType) {
        this.ratingId = ratingId;
        this.playerId = playerId;
        this.gameType = gameType;
        this.rating = DEFAULT_RATING;
        this.peakRating = DEFAULT_RATING;
    }

    /**
     * Apply the result of a game: update rating (clamped to >= 0), win/loss/draw
     * counters, games-played, peak rating and last-game timestamp.
     *
     * @param newRating    the new ELO rating computed by {@link EloCalculator}
     * @param actualScore  1.0 = win, 0.0 = loss, 0.5 = draw
     */
    public void applyResult(int newRating, double actualScore) {
        this.rating = Math.max(0, newRating);
        if (this.peakRating == null || this.rating > this.peakRating) {
            this.peakRating = this.rating;
        }
        if (actualScore >= 1.0) {
            this.wins = (this.wins == null ? 0 : this.wins) + 1;
        } else if (actualScore <= 0.0) {
            this.losses = (this.losses == null ? 0 : this.losses) + 1;
        } else {
            this.draws = (this.draws == null ? 0 : this.draws) + 1;
        }
        this.gamesPlayed = (this.gamesPlayed == null ? 0 : this.gamesPlayed) + 1;
        this.lastGameAt = Instant.now();
    }

    // --- Getters/Setters ---

    public Long getRatingId() { return ratingId; }
    public void setRatingId(Long ratingId) { this.ratingId = ratingId; }
    public Long getPlayerId() { return playerId; }
    public void setPlayerId(Long playerId) { this.playerId = playerId; }
    public GameType getGameType() { return gameType; }
    public void setGameType(GameType gameType) { this.gameType = gameType; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getRank() { return rank; }
    public void setRank(String rank) { this.rank = rank; }
    public Integer getWins() { return wins; }
    public void setWins(Integer wins) { this.wins = wins; }
    public Integer getLosses() { return losses; }
    public void setLosses(Integer losses) { this.losses = losses; }
    public Integer getDraws() { return draws; }
    public void setDraws(Integer draws) { this.draws = draws; }
    public Integer getGamesPlayed() { return gamesPlayed; }
    public void setGamesPlayed(Integer gamesPlayed) { this.gamesPlayed = gamesPlayed; }
    public Integer getPeakRating() { return peakRating; }
    public void setPeakRating(Integer peakRating) { this.peakRating = peakRating; }
    public Instant getLastGameAt() { return lastGameAt; }
    public void setLastGameAt(Instant lastGameAt) { this.lastGameAt = lastGameAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlayerRating that)) return false;
        return Objects.equals(ratingId, that.ratingId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ratingId);
    }
}
