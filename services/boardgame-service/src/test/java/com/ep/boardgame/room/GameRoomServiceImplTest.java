package com.ep.boardgame.room;

import com.ep.boardgame.event.PulsarClientProperties;
import com.ep.boardgame.event.PulsarEventPublisher;
import com.ep.cache.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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
 * D-149: GameRoomServiceImpl unit tests.
 * <p>
 * D-182: Tests also verify CacheService getOrLoad is called on reads and
 * evict is called after writes.
 */
class GameRoomServiceImplTest {

    private GameRoomRepository roomRepo;
    private GameMatchRepository matchRepo;
    private CacheService cacheService;
    private GameRoomServiceImpl service;

    @BeforeEach
    void setUp() {
        roomRepo = mock(GameRoomRepository.class);
        matchRepo = mock(GameMatchRepository.class);
        cacheService = mock(CacheService.class);
        // Cache miss: always invoke the loader so the real repo call happens.
        when(cacheService.getOrLoad(anyString(), anyString(), any(), anyLong(), anyBoolean()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> loader = inv.getArgument(2);
                    return loader.get();
                });
        when(roomRepo.save(any(GameRoom.class))).thenAnswer(inv -> {
            GameRoom r = inv.getArgument(0);
            if (r.getRoomId() == null) r.setRoomId(System.nanoTime() % 100000L);
            return r;
        });
        when(matchRepo.save(any(GameMatch.class))).thenAnswer(inv -> {
            GameMatch m = inv.getArgument(0);
            if (m.getMatchId() == null) m.setMatchId(System.nanoTime() % 100000L);
            return m;
        });
        service = new GameRoomServiceImpl(roomRepo, matchRepo, noopPublisher(), cacheService);
    }

    private static PulsarEventPublisher noopPublisher() {
        ObjectProvider<org.apache.pulsar.client.api.PulsarClient> cp = mock(ObjectProvider.class);
        when(cp.getIfAvailable()).thenReturn(null);
        PulsarClientProperties props = new PulsarClientProperties();
        PulsarEventPublisher pub = new PulsarEventPublisher(cp, props, false);
        pub.init();
        return pub;
    }

    private CreateRoomCmd cmd(String name) {
        CreateRoomCmd c = new CreateRoomCmd();
        c.setTenantId(10L);
        c.setRoomName(name);
        return c;
    }

    @Test
    void createRoom_success() {
        GameRoom room = service.createRoom(cmd("Test Room"));
        assertNotNull(room.getRoomId());
        assertEquals("Test Room", room.getRoomName());
        assertEquals(GameRoom.STATUS_WAITING, room.getStatus());
        assertNotNull(room.getCreatedAt());
        verify(cacheService).evict(eq("boardgame:room"), anyString());
    }

    @Test
    void createRoom_blankName_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.createRoom(cmd("")));
    }

    @Test
    void createRoom_nullTenantId_throws() {
        CreateRoomCmd c = cmd("Test");
        c.setTenantId(null);
        assertThrows(NullPointerException.class, () -> service.createRoom(c));
    }

    @Test
    void getRoomById_found() {
        GameRoom room = newRoom(1L);
        when(roomRepo.findById(1L)).thenReturn(Optional.of(room));
        assertNotNull(service.getRoomById(1L));
        verify(cacheService).getOrLoad(eq("boardgame:room"), eq("1"), any(), eq(60L), eq(true));
    }

    @Test
    void getRoomById_notFound() {
        when(roomRepo.findById(999L)).thenReturn(Optional.empty());
        assertNull(service.getRoomById(999L));
        verify(cacheService).getOrLoad(eq("boardgame:room"), eq("999"), any(), eq(60L), eq(true));
    }

    @Test
    void listRooms_byTenant() {
        when(roomRepo.findByTenantIdOrderByRoomIdDesc(10L))
                .thenReturn(List.of(newRoom(1L)));
        assertEquals(1, service.listRooms(10L).size());
    }

    @Test
    void listRooms_all() {
        when(roomRepo.findAllByOrderByRoomIdDesc())
                .thenReturn(List.of(newRoom(1L), newRoom(2L)));
        assertEquals(2, service.listRooms(null).size());
    }

    @Test
    void listWaitingRooms_success() {
        when(roomRepo.findByStatusAndTenantId(GameRoom.STATUS_WAITING, 10L))
                .thenReturn(List.of(newRoom(1L)));
        assertEquals(1, service.listWaitingRooms(10L).size());
    }

    @Test
    void joinRoom_success() {
        GameRoom room = newRoom(1L);
        when(roomRepo.findById(1L)).thenReturn(Optional.of(room));
        service.joinRoom(1L, 100L);
        assertEquals(100L, room.getRedPlayerId());
        verify(cacheService).evict("boardgame:room", "1");
    }

    @Test
    void joinRoom_notFound_throws() {
        when(roomRepo.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.joinRoom(999L, 100L));
    }

    @Test
    void leaveRoom_success() {
        GameRoom room = newRoom(1L);
        room.join(100L);
        when(roomRepo.findById(1L)).thenReturn(Optional.of(room));
        service.leaveRoom(1L, 100L);
        assertNull(room.getRedPlayerId());
        verify(cacheService).evict("boardgame:room", "1");
    }

    @Test
    void startMatch_success() {
        GameRoom room = newRoom(1L);
        room.join(100L);
        room.join(200L);
        when(roomRepo.findById(1L)).thenReturn(Optional.of(room));
        GameMatch match = service.startMatch(1L);
        assertNotNull(match.getMatchId());
        assertEquals(GameRoom.STATUS_PLAYING, room.getStatus());
        assertEquals(GameMatch.RESULT_ONGOING, match.getResult());
        assertEquals(100L, match.getRedPlayerId());
        assertEquals(200L, match.getBlackPlayerId());
        verify(cacheService).evict("boardgame:room", "1");
    }

    @Test
    void startMatch_notEnoughPlayers_throws() {
        GameRoom room = newRoom(1L);
        room.join(100L);
        when(roomRepo.findById(1L)).thenReturn(Optional.of(room));
        assertThrows(IllegalStateException.class, () -> service.startMatch(1L));
    }

    @Test
    void makeMove_legalMove_success() {
        GameRoom room = newRoom(1L);
        room.join(100L);
        room.join(200L);
        when(roomRepo.findById(1L)).thenReturn(Optional.of(room));
        GameMatch match = service.startMatch(1L);
        when(matchRepo.findById(match.getMatchId())).thenReturn(Optional.of(match));

        MakeMoveCmd cmd = new MakeMoveCmd();
        cmd.setMatchId(match.getMatchId());
        cmd.setPlayerId(100L); // RED player
        cmd.setFromRow(0);
        cmd.setFromCol(1);
        cmd.setToRow(2);
        cmd.setToCol(2);

        GameMatch result = service.makeMove(cmd);
        assertEquals(1, result.getMoveCount());
        assertEquals(1, result.getCurrentSide()); // BLACK's turn
        verify(cacheService).evict("boardgame:match", String.valueOf(match.getMatchId()));
    }

    @Test
    void makeMove_illegalMove_throws() {
        GameRoom room = newRoom(1L);
        room.join(100L);
        room.join(200L);
        when(roomRepo.findById(1L)).thenReturn(Optional.of(room));
        GameMatch match = service.startMatch(1L);
        when(matchRepo.findById(match.getMatchId())).thenReturn(Optional.of(match));

        MakeMoveCmd cmd = new MakeMoveCmd();
        cmd.setMatchId(match.getMatchId());
        cmd.setPlayerId(100L);
        cmd.setFromRow(0);
        cmd.setFromCol(0);
        cmd.setToRow(3);
        cmd.setToCol(0); // Blocked chariot (cannon at row 2)
        assertThrows(IllegalArgumentException.class, () -> service.makeMove(cmd));
    }

    @Test
    void resign_redResigns_blackWins() {
        GameRoom room = newRoom(1L);
        room.join(100L);
        room.join(200L);
        when(roomRepo.findById(1L)).thenReturn(Optional.of(room));
        GameMatch match = service.startMatch(1L);
        when(matchRepo.findById(match.getMatchId())).thenReturn(Optional.of(match));

        GameMatch result = service.resign(match.getMatchId(), 100L);
        assertEquals(GameMatch.RESULT_BLACK_WIN, result.getResult());
        verify(cacheService).evict("boardgame:match", String.valueOf(match.getMatchId()));
    }

    @Test
    void getMatchById_found() {
        GameMatch match = newMatch(1L);
        when(matchRepo.findById(1L)).thenReturn(Optional.of(match));
        assertNotNull(service.getMatchById(1L));
        verify(cacheService).getOrLoad(eq("boardgame:match"), eq("1"), any(), eq(60L), eq(true));
    }

    @Test
    void getMatchById_notFound() {
        when(matchRepo.findById(999L)).thenReturn(Optional.empty());
        assertNull(service.getMatchById(999L));
        verify(cacheService).getOrLoad(eq("boardgame:match"), eq("999"), any(), eq(60L), eq(true));
    }

    @Test
    void listMatchesByPlayer_success() {
        when(matchRepo.findByRedPlayerIdOrBlackPlayerIdOrderByMatchIdDesc(100L, 100L))
                .thenReturn(List.of(newMatch(1L)));
        assertEquals(1, service.listMatchesByPlayer(100L).size());
    }

    @Test
    void listMatchesByTenant_success() {
        when(matchRepo.findByTenantIdOrderByMatchIdDesc(10L))
                .thenReturn(List.of(newMatch(1L)));
        assertEquals(1, service.listMatchesByTenant(10L).size());
    }

    @Test
    void getRoomById_cache_hit_skips_repository() {
        // Simulate a cache hit: getOrLoad returns the room without invoking the loader.
        GameRoom cached = newRoom(1L);
        when(cacheService.getOrLoad(eq("boardgame:room"), eq("1"), any(), eq(60L), eq(true)))
                .thenReturn(cached);
        GameRoom result = service.getRoomById(1L);
        assertNotNull(result);
        assertEquals(1L, result.getRoomId());
        // Repository must NOT be called on a cache hit.
        verify(roomRepo, never()).findById(anyLong());
    }

    // --- Helpers ---

    private GameRoom newRoom(Long id) {
        GameRoom room = new GameRoom();
        room.setRoomId(id);
        room.setTenantId(10L);
        room.setRoomName("test-" + id);
        room.setStatus(GameRoom.STATUS_WAITING);
        room.setCreatedAt(System.currentTimeMillis());
        room.setUpdatedAt(System.currentTimeMillis());
        return room;
    }

    private GameMatch newMatch(Long id) {
        GameMatch match = new GameMatch();
        match.setMatchId(id);
        match.setRoomId(1L);
        match.setTenantId(10L);
        match.setRedPlayerId(100L);
        match.setBlackPlayerId(200L);
        match.setFenState("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1");
        match.setMoveHistory("[]");
        match.setResult(GameMatch.RESULT_ONGOING);
        match.setMoveCount(0);
        match.setCurrentSide(0);
        match.setCreatedAt(System.currentTimeMillis());
        return match;
    }
}
