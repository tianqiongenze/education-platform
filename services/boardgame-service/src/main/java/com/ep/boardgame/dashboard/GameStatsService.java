package com.ep.boardgame.dashboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * D-192: Dashboard stats service.
 * <p>
 * Aggregates rating history, win/loss/draw counts and achievement progress for
 * the player dashboard. Reads from {@code bg_player_rating} (rating history)
 * and {@code bg_game_match} (completed games) via {@link JdbcTemplate}.
 * <p>
 * Both tables are created lazily by the platform; if a player has no data the
 * service returns empty lists / zeroed stats rather than failing.
 */
@Service
public class GameStatsService {

    private final JdbcTemplate jdbc;

    public GameStatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Rating progress time series for the player across all game types.
     */
    public List<RatingPoint> getRatingProgress(Long playerId) {
        return jdbc.query("""
                SELECT recorded_at, rating, game_type
                FROM bg_player_rating
                WHERE player_id = ?
                ORDER BY recorded_at ASC
                """, (rs, rowNum) -> new RatingPoint(
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getInt("rating"),
                rs.getString("game_type")), playerId);
    }

    /**
     * Win/loss/draw aggregate per game type for the player.
     * <p>
     * A player "wins" when they are red and result=RED_WIN, or black and
     * result=BLACK_WIN. Draws are result=DRAW.
     */
    public List<WinLossStats> getWinLossStats(Long playerId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT
                    'XIANGQI' AS game_type,
                    SUM(CASE WHEN (red_player_id=? AND result=1) OR (black_player_id=? AND result=2) THEN 1 ELSE 0 END) AS wins,
                    SUM(CASE WHEN (red_player_id=? AND result=2) OR (black_player_id=? AND result=1) THEN 1 ELSE 0 END) AS losses,
                    SUM(CASE WHEN result=3 THEN 1 ELSE 0 END) AS draws,
                    COUNT(*) AS total
                FROM bg_game_match
                WHERE (red_player_id=? OR black_player_id=?) AND result <> 0
                """, playerId, playerId, playerId, playerId, playerId, playerId);
        List<WinLossStats> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String gameType = (String) row.get("game_type");
            int wins = toInt(row.get("wins"));
            int losses = toInt(row.get("losses"));
            int draws = toInt(row.get("draws"));
            int total = toInt(row.get("total"));
            if (total == 0) continue;
            result.add(new WinLossStats(gameType, wins, losses, draws, total));
        }
        return result;
    }

    /**
     * Achievement progress: badges based on rating thresholds and games played.
     */
    public List<AchievementProgress> getAchievementProgress(Long playerId) {
        // Current max rating (any game type).
        List<Integer> ratings = jdbc.queryForList(
                "SELECT rating FROM bg_player_rating WHERE player_id=? ORDER BY recorded_at DESC LIMIT 1",
                Integer.class, playerId);
        int currentRating = ratings.isEmpty() ? 0 : ratings.get(0);

        // Total games played.
        Integer totalGames = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bg_game_match WHERE (red_player_id=? OR black_player_id=?) AND result <> 0",
                Integer.class, playerId, playerId);
        int gamesPlayed = totalGames == null ? 0 : totalGames;

        List<AchievementProgress> achievements = new ArrayList<>();
        achievements.add(thresholdAchievement("FIRST_GAME", "First Steps",
                "Play your first game", gamesPlayed >= 1, gamesPlayed, 1));
        achievements.add(thresholdAchievement("GAMES_10", "Getting Warmer",
                "Play 10 games", gamesPlayed >= 10, gamesPlayed, 10));
        achievements.add(thresholdAchievement("GAMES_50", "Seasoned Player",
                "Play 50 games", gamesPlayed >= 50, gamesPlayed, 50));
        achievements.add(thresholdAchievement("RATING_1200", "Rising Star",
                "Reach 1200 rating", currentRating >= 1200, currentRating, 1200));
        achievements.add(thresholdAchievement("RATING_1500", "Intermediate",
                "Reach 1500 rating", currentRating >= 1500, currentRating, 1500));
        achievements.add(thresholdAchievement("RATING_2000", "Expert",
                "Reach 2000 rating", currentRating >= 2000, currentRating, 2000));
        return achievements;
    }

    private AchievementProgress thresholdAchievement(String code, String name,
                                                     String desc, boolean unlocked,
                                                     int current, int target) {
        double progress = target == 0 ? 1.0 : Math.min(1.0, (double) current / target);
        return new AchievementProgress(code, name, desc, unlocked, progress);
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
