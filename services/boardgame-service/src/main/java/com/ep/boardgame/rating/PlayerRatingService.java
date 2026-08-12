package com.ep.boardgame.rating;

import com.ep.boardgame.tournament.Tournament.GameType;

/**
 * D-191: Domain service for player ratings.
 * <p>
 * Provides rating lookup, creation-on-first-game, and post-game rating updates
 * using {@link EloCalculator}.
 */
public interface PlayerRatingService {

    /**
     * Look up a player's rating for a game type, creating a fresh 1200 rating
     * record (persisted) if none exists.
     */
    PlayerRating getOrCreate(Long playerId, GameType gameType);

    /**
     * Record the result of a game between two players, updating both ratings.
     *
     * @param playerId       the (possibly winning) player
     * @param opponentId     the opponent
     * @param gameType       the game type
     * @param playerScore    1.0 = player win, 0.0 = player loss, 0.5 = draw
     * @return the updated {@link PlayerRating} for {@code playerId}
     */
    PlayerRating recordResult(Long playerId, Long opponentId, GameType gameType, double playerScore);

    /**
     * Find a player's rating without creating a new record.
     */
    PlayerRating findById(Long ratingId);
}
