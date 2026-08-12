package com.ep.boardgame.matchmaking;

import com.ep.boardgame.room.GameRoom;
import com.ep.boardgame.room.GameRoomService;
import com.ep.boardgame.tournament.Tournament.GameType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.TreeSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-191: {@link MatchmakingService} unit tests. Redis is mocked via
 * {@link StringRedisTemplate} mocks (no live Redis required).
 */
class MatchmakingServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ZSetOperations<String, String> zsetOps;
    private GameRoomService gameRoomService;
    private MatchmakingService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        zsetOps = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zsetOps);

        gameRoomService = mock(GameRoomService.class);
        when(gameRoomService.createRoom(any())).thenAnswer(inv -> {
            GameRoom r = new GameRoom();
            r.setRoomId(1L);
            r.setStatus(GameRoom.STATUS_WAITING);
            return r;
        });
        when(gameRoomService.joinRoom(anyLong(), anyLong())).thenAnswer(inv -> {
            GameRoom r = new GameRoom();
            r.setRoomId(1L);
            r.setStatus(GameRoom.STATUS_READY);
            return r;
        });
        when(gameRoomService.getRoomById(anyLong())).thenAnswer(inv -> {
            GameRoom r = new GameRoom();
            r.setRoomId(1L);
            r.setStatus(GameRoom.STATUS_READY);
            return r;
        });
        service = new MatchmakingService(redis, gameRoomService);
    }

    @Test
    void joinQueue_addsPlayer() {
        when(zsetOps.add(eq("mm:queue:XIANGQI"), eq("100"), eq(1500.0))).thenReturn(true);
        assertTrue(service.joinQueue(100L, GameType.XIANGQI, 1500));
    }

    @Test
    void joinQueue_alreadyQueued_returnsFalse() {
        when(zsetOps.add(anyString(), anyString(), anyDouble())).thenReturn(false);
        assertFalse(service.joinQueue(100L, GameType.XIANGQI, 1500));
    }

    @Test
    void joinQueue_nullArgs_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.joinQueue(null, GameType.GO, 1500));
        assertThrows(IllegalArgumentException.class,
                () -> service.joinQueue(1L, null, 1500));
    }

    @Test
    void leaveQueue_removesPlayer() {
        when(zsetOps.remove("mm:queue:GO", "100")).thenReturn(1L);
        assertTrue(service.leaveQueue(100L, GameType.GO));
    }

    @Test
    void leaveQueue_notQueued_returnsFalse() {
        when(zsetOps.remove(anyString(), anyString())).thenReturn(0L);
        assertFalse(service.leaveQueue(999L, GameType.XIANGQI));
    }

    @Test
    void tryMatch_emptyQueue_returnsEmpty() {
        when(zsetOps.range("mm:queue:XIANGQI", 0, 0)).thenReturn(new TreeSet<>());
        assertFalse(service.tryMatch(GameType.XIANGQI).isPresent());
    }

    @Test
    void tryMatch_singlePlayer_returnsEmpty() {
        Set<String> lowest = new LinkedHashSet<>();
        lowest.add("100");
        when(zsetOps.range("mm:queue:XIANGQI", 0, 0)).thenReturn(lowest);
        when(zsetOps.score("mm:queue:XIANGQI", "100")).thenReturn(1500.0);
        // rangeByScore returns only the one player -> size < 2.
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add("100");
        when(zsetOps.rangeByScore("mm:queue:XIANGQI", 1300.0, 1700.0)).thenReturn(candidates);
        assertFalse(service.tryMatch(GameType.XIANGQI).isPresent());
    }

    @Test
    void tryMatch_twoPlayersWithinWindow_createsRoom() {
        Set<String> lowest = new LinkedHashSet<>();
        lowest.add("100");
        when(zsetOps.range("mm:queue:XIANGQI", 0, 0)).thenReturn(lowest);
        when(zsetOps.score("mm:queue:XIANGQI", "100")).thenReturn(1500.0);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add("100");
        candidates.add("200");
        when(zsetOps.rangeByScore("mm:queue:XIANGQI", 1300.0, 1700.0)).thenReturn(candidates);
        when(zsetOps.remove("mm:queue:XIANGQI", "100")).thenReturn(1L);
        when(zsetOps.remove("mm:queue:XIANGQI", "200")).thenReturn(1L);
        Optional<GameRoom> room = service.tryMatch(GameType.XIANGQI);
        assertTrue(room.isPresent());
        assertNotNull(room.get().getRoomId());
    }

    @Test
    void tryMatch_playersOutsideWindow_returnsEmpty() {
        Set<String> lowest = new LinkedHashSet<>();
        lowest.add("100");
        when(zsetOps.range("mm:queue:XIANGQI", 0, 0)).thenReturn(lowest);
        when(zsetOps.score("mm:queue:XIANGQI", "100")).thenReturn(1000.0);
        // rangeByScore window 800-1200 returns only player 100.
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add("100");
        when(zsetOps.rangeByScore("mm:queue:XIANGQI", 800.0, 1200.0)).thenReturn(candidates);
        assertFalse(service.tryMatch(GameType.XIANGQI).isPresent());
    }

    @Test
    void tryMatch_raceLost_returnsEmpty() {
        Set<String> lowest = new LinkedHashSet<>();
        lowest.add("100");
        when(zsetOps.range("mm:queue:XIANGQI", 0, 0)).thenReturn(lowest);
        when(zsetOps.score("mm:queue:XIANGQI", "100")).thenReturn(1500.0);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add("100");
        candidates.add("200");
        when(zsetOps.rangeByScore("mm:queue:XIANGQI", 1300.0, 1700.0)).thenReturn(candidates);
        // Simulate another worker removing the opponent first.
        when(zsetOps.remove("mm:queue:XIANGQI", "100")).thenReturn(1L);
        when(zsetOps.remove("mm:queue:XIANGQI", "200")).thenReturn(0L);
        assertFalse(service.tryMatch(GameType.XIANGQI).isPresent());
    }

    @Test
    void tryMatchWithBotTimeout_emptyQueue_returnsEmpty() {
        when(zsetOps.range("mm:queue:GO", 0, -1)).thenReturn(new TreeSet<>());
        assertFalse(service.tryMatchWithBotTimeout(GameType.GO).isPresent());
    }

    @Test
    void tryMatchWithBotTimeout_notStale_returnsEmpty() {
        Set<String> all = new LinkedHashSet<>();
        all.add("100");
        when(zsetOps.range("mm:queue:GO", 0, -1)).thenReturn(all);
        when(valueOps.get("mm:queuetime:GO:100")).thenReturn(String.valueOf(System.currentTimeMillis()));
        assertFalse(service.tryMatchWithBotTimeout(GameType.GO).isPresent());
    }

    @Test
    void tryMatchWithBotTimeout_stalePlayer_matchesBot() {
        Set<String> all = new LinkedHashSet<>();
        all.add("100");
        when(zsetOps.range("mm:queue:GO", 0, -1)).thenReturn(all);
        when(valueOps.get("mm:queuetime:GO:100"))
                .thenReturn(String.valueOf(System.currentTimeMillis() - 31_000));
        when(zsetOps.remove("mm:queue:GO", "100")).thenReturn(1L);
        Optional<GameRoom> room = service.tryMatchWithBotTimeout(GameType.GO);
        assertTrue(room.isPresent());
        assertEquals(GameRoom.STATUS_READY, room.get().getStatus());
    }

    @Test
    void tryMatchWithBotTimeout_nullTimestamp_skipsPlayer() {
        Set<String> all = new LinkedHashSet<>();
        all.add("100");
        when(zsetOps.range("mm:queue:GO", 0, -1)).thenReturn(all);
        when(valueOps.get("mm:queuetime:GO:100")).thenReturn(null);
        assertFalse(service.tryMatchWithBotTimeout(GameType.GO).isPresent());
    }
}
