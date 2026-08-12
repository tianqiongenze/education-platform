package com.ep.boardgame.replay;

import com.ep.boardgame.room.GameMatch;
import com.ep.cache.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * D-182: GameReplayService unit tests — verifies cache integration.
 */
class GameReplayServiceTest {

    private GameReplayRepository replayRepo;
    private CacheService cacheService;
    private GameReplayService service;

    @BeforeEach
    void setUp() {
        replayRepo = mock(GameReplayRepository.class);
        cacheService = mock(CacheService.class);
        // Cache miss: invoke the loader so the real repo call happens.
        when(cacheService.getOrLoad(anyString(), anyString(), any(), anyLong(), anyBoolean()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> loader = inv.getArgument(2);
                    return loader.get();
                });
        when(replayRepo.save(any(GameReplay.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new GameReplayService(replayRepo, cacheService);
    }

    @Test
    void createReplay_success_evicts_cache() {
        GameMatch match = newMatch();
        GameReplay replay = service.createReplay(match, 99L);
        assertNotNull(replay);
        assertEquals(match.getMatchId(), replay.getMatchId());
        assertEquals(match.getTenantId(), replay.getTenantId());
        verify(cacheService).evict("boardgame:replay", String.valueOf(match.getMatchId()));
        verify(cacheService).evict("boardgame:replay:by-tenant", String.valueOf(match.getTenantId()));
        verify(cacheService).evict("boardgame:replay:by-course", String.valueOf(match.getCourseId()));
    }

    @Test
    void getReplayByMatch_found() {
        GameReplay replay = newReplay(1L);
        when(replayRepo.findByMatchId(1L)).thenReturn(List.of(replay));
        GameReplay result = service.getReplayByMatch(1L);
        assertNotNull(result);
        assertEquals(1L, result.getReplayId());
        verify(cacheService).getOrLoad(eq("boardgame:replay"), eq("1"), any(), eq(600L), eq(true));
    }

    @Test
    void getReplayByMatch_notFound() {
        when(replayRepo.findByMatchId(999L)).thenReturn(List.of());
        assertNull(service.getReplayByMatch(999L));
        verify(cacheService).getOrLoad(eq("boardgame:replay"), eq("999"), any(), eq(600L), eq(true));
    }

    @Test
    void listReplaysByTenant_success() {
        when(replayRepo.findByTenantIdOrderByReplayIdDesc(10L))
                .thenReturn(List.of(newReplay(1L)));
        assertEquals(1, service.listReplaysByTenant(10L).size());
        verify(cacheService).getOrLoad(eq("boardgame:replay:by-tenant"), eq("10"), any(), eq(600L), eq(false));
    }

    @Test
    void listReplaysByCourse_success() {
        when(replayRepo.findByCourseIdOrderByReplayIdDesc(5L))
                .thenReturn(List.of(newReplay(1L)));
        assertEquals(1, service.listReplaysByCourse(5L).size());
        verify(cacheService).getOrLoad(eq("boardgame:replay:by-course"), eq("5"), any(), eq(600L), eq(false));
    }

    @Test
    void getReplayByMatch_cache_hit_skips_repository() {
        GameReplay cached = newReplay(1L);
        when(cacheService.getOrLoad(eq("boardgame:replay"), eq("1"), any(), eq(600L), eq(true)))
                .thenReturn(cached);
        GameReplay result = service.getReplayByMatch(1L);
        assertNotNull(result);
        verify(replayRepo, never()).findByMatchId(anyLong());
    }

    // --- Helpers ---

    private GameMatch newMatch() {
        GameMatch match = new GameMatch();
        match.setMatchId(1L);
        match.setRoomId(1L);
        match.setTenantId(10L);
        match.setCourseId(5L);
        match.setRedPlayerId(100L);
        match.setBlackPlayerId(200L);
        match.setFenState("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1");
        match.setMoveHistory("[]");
        match.setResult(GameMatch.RESULT_RED_WIN);
        match.setMoveCount(10);
        match.setCurrentSide(0);
        match.setCreatedAt(System.currentTimeMillis());
        return match;
    }

    private GameReplay newReplay(Long id) {
        GameReplay replay = new GameReplay();
        replay.setReplayId(id);
        replay.setMatchId(1L);
        replay.setTenantId(10L);
        replay.setRoomId(1L);
        replay.setCourseId(5L);
        replay.setLessonId(99L);
        replay.setRedPlayerId(100L);
        replay.setBlackPlayerId(200L);
        replay.setResult(GameMatch.RESULT_RED_WIN);
        replay.setMoveCount(10);
        replay.setMoveHistory("[]");
        replay.setInitialFen("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1");
        replay.setCreatedAt(System.currentTimeMillis());
        return replay;
    }
}
