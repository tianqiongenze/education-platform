package com.ep.boardgame.rating;

import com.ep.boardgame.common.SnowflakeIdGenerator;
import com.ep.boardgame.tournament.Tournament.GameType;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * D-191: {@link PlayerRatingService} implementation.
 * <p>
 * Uses constructor injection and Snowflake IDs. Rating updates apply the
 * ELO formula symmetrically to both players (winner +, loser -, draw neutral).
 */
@DubboService
public class PlayerRatingServiceImpl implements PlayerRatingService {

    private final PlayerRatingRepository ratingRepo;

    public PlayerRatingServiceImpl(PlayerRatingRepository ratingRepo) {
        this.ratingRepo = ratingRepo;
    }

    @Override
    @Transactional
    public PlayerRating getOrCreate(Long playerId, GameType gameType) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(gameType, "gameType");
        return ratingRepo.findByPlayerIdAndGameType(playerId, gameType)
                .orElseGet(() -> {
                    PlayerRating r = new PlayerRating(
                            SnowflakeIdGenerator.getInstance().nextId(),
                            playerId, gameType);
                    return ratingRepo.save(r);
                });
    }

    @Override
    @Transactional
    public PlayerRating recordResult(Long playerId, Long opponentId, GameType gameType,
                                     double playerScore) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(opponentId, "opponentId");
        Objects.requireNonNull(gameType, "gameType");
        if (playerId.equals(opponentId)) {
            throw new IllegalArgumentException("player and opponent must differ");
        }
        if (playerScore < 0.0 || playerScore > 1.0) {
            throw new IllegalArgumentException("playerScore must be in [0, 1]");
        }

        PlayerRating playerRating = getOrCreate(playerId, gameType);
        PlayerRating opponentRating = getOrCreate(opponentId, gameType);

        int newPlayerRating = EloCalculator.newRating(
                playerRating.getRating(), opponentRating.getRating(), playerScore);
        double opponentScore = 1.0 - playerScore;
        int newOpponentRating = EloCalculator.newRating(
                opponentRating.getRating(), playerRating.getRating(), opponentScore);

        playerRating.applyResult(newPlayerRating, playerScore);
        opponentRating.applyResult(newOpponentRating, opponentScore);

        ratingRepo.save(opponentRating);
        return ratingRepo.save(playerRating);
    }

    @Override
    public PlayerRating findById(Long ratingId) {
        return ratingRepo.findById(ratingId).orElse(null);
    }
}
