package com.ep.boardgame.rating;

import com.ep.boardgame.tournament.Tournament.GameType;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * D-191: JPA repository for {@link PlayerRating}.
 */
public interface PlayerRatingRepository extends JpaRepository<PlayerRating, Long> {

    Optional<PlayerRating> findByPlayerIdAndGameType(Long playerId, GameType gameType);
}
