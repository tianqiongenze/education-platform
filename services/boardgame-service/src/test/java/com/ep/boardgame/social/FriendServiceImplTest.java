package com.ep.boardgame.social;

import com.ep.boardgame.room.GameMatch;
import com.ep.boardgame.room.GameMatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * D-192: FriendServiceImpl unit tests (mocked JdbcTemplate).
 */
class FriendServiceImplTest {

    private JdbcTemplate jdbc;
    private GameMatchRepository matchRepo;
    private FriendServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        matchRepo = mock(GameMatchRepository.class);
        service = new FriendServiceImpl(jdbc, matchRepo);
    }

    @Test
    void addFriend_insertsPendingRow() {
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        Friendship f = service.addFriend(1L, 2L);
        assertEquals(Friendship.Status.PENDING, f.getStatus());
        assertEquals(1L, f.getPlayerId());
        assertEquals(2L, f.getFriendId());
    }

    @Test
    void addFriend_self_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.addFriend(1L, 1L));
    }

    @Test
    void acceptFriend_updatesAndInsertsReverse() {
        // acceptFriend(playerId=1, friendId=2) updates row where player_id=2, friend_id=1
        // First update (5 args: now, friendId=2, playerId=1) must return 1 (row updated)
        when(jdbc.update(anyString(), any(), eq(2L), eq(1L))).thenReturn(1);
        // Second update (insert reverse, 4 args: playerId=1, friendId=2, now, now)
        when(jdbc.update(anyString(), eq(1L), eq(2L), any(), any())).thenReturn(1);
        Friendship f = service.acceptFriend(1L, 2L);
        assertEquals(Friendship.Status.ACCEPTED, f.getStatus());
    }

    @Test
    void acceptFriend_noPendingRequest_throws() {
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.acceptFriend(1L, 2L));
    }

    @Test
    void removeFriend_deletesBothDirections() {
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(2);
        service.removeFriend(1L, 2L);
        verify(jdbc).update(anyString(), eq(1L), eq(2L), eq(2L), eq(1L));
    }

    @Test
    void listMutualFriends_intersectsFriendSets() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(1L), eq(1L), eq(1L)))
                .thenReturn(List.of(10L, 20L, 30L));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(2L), eq(2L), eq(2L)))
                .thenReturn(List.of(20L, 30L, 40L));
        List<Long> mutual = service.listMutualFriends(1L, 2L);
        assertEquals(List.of(20L, 30L), mutual);
    }

    @Test
    void getMatchHistory_filtersToMatchesBetweenPlayers() {
        GameMatch m1 = match(101L, 1L, 2L); // between 1 and 2
        GameMatch m2 = match(102L, 1L, 3L); // not between 1 and 2
        when(matchRepo.findByRedPlayerIdOrBlackPlayerIdOrderByMatchIdDesc(eq(1L), eq(1L)))
                .thenReturn(List.of(m1, m2));
        List<GameMatch> history = service.getMatchHistory(1L, 2L);
        assertEquals(1, history.size());
        assertEquals(101L, history.get(0).getMatchId());
    }

    private GameMatch match(Long id, Long red, Long black) {
        GameMatch m = new GameMatch();
        m.setMatchId(id);
        m.setRedPlayerId(red);
        m.setBlackPlayerId(black);
        return m;
    }
}
