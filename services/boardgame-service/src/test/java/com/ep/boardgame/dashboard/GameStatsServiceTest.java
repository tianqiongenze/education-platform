package com.ep.boardgame.dashboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * D-192: GameStatsService unit tests (mocked JdbcTemplate).
 */
class GameStatsServiceTest {

    private JdbcTemplate jdbc;
    private GameStatsService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new GameStatsService(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getRatingProgress_returnsTimeSeries() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L)))
                .thenReturn(List.of(
                        new RatingPoint(Instant.parse("2026-01-01T00:00:00Z"), 1000, "XIANGQI"),
                        new RatingPoint(Instant.parse("2026-02-01T00:00:00Z"), 1100, "XIANGQI")));
        List<RatingPoint> points = service.getRatingProgress(1L);
        assertEquals(2, points.size());
        assertEquals(1100, points.get(1).rating());
    }

    @Test
    void getWinLossStats_aggregates() {
        Map<String, Object> row = new HashMap<>();
        row.put("game_type", "XIANGQI");
        row.put("wins", 5L);
        row.put("losses", 3L);
        row.put("draws", 2L);
        row.put("total", 10L);
        when(jdbc.queryForList(anyString(), eq(1L), eq(1L), eq(1L), eq(1L), eq(1L), eq(1L)))
                .thenReturn(List.of(row));
        List<WinLossStats> stats = service.getWinLossStats(1L);
        assertEquals(1, stats.size());
        WinLossStats s = stats.get(0);
        assertEquals(5, s.wins());
        assertEquals(3, s.losses());
        assertEquals(2, s.draws());
        assertEquals(10, s.total());
    }

    @Test
    void getWinLossStats_empty_returnsEmpty() {
        Map<String, Object> row = new HashMap<>();
        row.put("game_type", "XIANGQI");
        row.put("wins", 0L);
        row.put("losses", 0L);
        row.put("draws", 0L);
        row.put("total", 0L);
        when(jdbc.queryForList(anyString(), eq(1L), eq(1L), eq(1L), eq(1L), eq(1L), eq(1L)))
                .thenReturn(List.of(row));
        assertTrue(service.getWinLossStats(1L).isEmpty());
    }

    @Test
    void getAchievementProgress_unlocksBadges() {
        when(jdbc.queryForList(anyString(), eq(Integer.class), eq(1L)))
                .thenReturn(List.of(1500));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L), eq(1L)))
                .thenReturn(50);

        List<AchievementProgress> achievements = service.getAchievementProgress(1L);

        Map<String, AchievementProgress> byCode = new java.util.HashMap<>();
        for (AchievementProgress a : achievements) byCode.put(a.code(), a);

        assertTrue(byCode.get("FIRST_GAME").unlocked());
        assertTrue(byCode.get("GAMES_10").unlocked());
        assertTrue(byCode.get("GAMES_50").unlocked());
        assertTrue(byCode.get("RATING_1200").unlocked());
        assertTrue(byCode.get("RATING_1500").unlocked());
        assertFalse(byCode.get("RATING_2000").unlocked());
        assertTrue(byCode.get("RATING_2000").progress() < 1.0);
    }

    @Test
    void getAchievementProgress_noData_returnsLocked() {
        when(jdbc.queryForList(anyString(), eq(Integer.class), eq(1L)))
                .thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L), eq(1L)))
                .thenReturn(0);

        List<AchievementProgress> achievements = service.getAchievementProgress(1L);
        assertTrue(achievements.stream().noneMatch(AchievementProgress::unlocked));
    }
}
