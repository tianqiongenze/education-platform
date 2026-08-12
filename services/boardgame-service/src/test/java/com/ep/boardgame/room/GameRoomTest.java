package com.ep.boardgame.room;

import com.ep.boardgame.common.DomainEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-149: GameRoom aggregate root state machine tests.
 */
class GameRoomTest {

    private GameRoom newWaitingRoom() {
        GameRoom room = new GameRoom();
        room.setRoomId(1L);
        room.setTenantId(10L);
        room.setRoomName("Test Room");
        room.setStatus(GameRoom.STATUS_WAITING);
        room.setCreatedAt(System.currentTimeMillis());
        room.setUpdatedAt(System.currentTimeMillis());
        return room;
    }

    @Test
    void join_firstPlayer_becomesRed() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        assertEquals(100L, room.getRedPlayerId());
        assertNull(room.getBlackPlayerId());
    }

    @Test
    void join_secondPlayer_becomesBlack() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        room.join(200L);
        assertEquals(100L, room.getRedPlayerId());
        assertEquals(200L, room.getBlackPlayerId());
    }

    @Test
    void join_samePlayerTwice_throws() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        assertThrows(IllegalArgumentException.class, () -> room.join(100L));
    }

    @Test
    void join_roomFull_throws() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        room.join(200L);
        assertThrows(IllegalStateException.class, () -> room.join(300L));
    }

    @Test
    void join_notWaiting_throws() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        room.join(200L);
        room.start(999L);
        assertThrows(IllegalStateException.class, () -> room.join(300L));
    }

    @Test
    void leave_waitingRoom_success() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        room.leave(100L);
        assertNull(room.getRedPlayerId());
    }

    @Test
    void leave_playingRoom_throws() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        room.join(200L);
        room.start(999L);
        assertThrows(IllegalStateException.class, () -> room.leave(100L));
    }

    @Test
    void leave_notInRoom_throws() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        assertThrows(IllegalArgumentException.class, () -> room.leave(999L));
    }

    @Test
    void start_bothPlayersPresent_success() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        room.join(200L);
        room.start(999L);
        assertEquals(GameRoom.STATUS_PLAYING, room.getStatus());
        assertEquals(999L, room.getMatchId());
        List<DomainEvent> events = room.pullEvents();
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(e -> e instanceof RoomStartedEvent));
    }

    @Test
    void start_missingBlack_throws() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        assertThrows(IllegalStateException.class, () -> room.start(999L));
    }

    @Test
    void start_notWaiting_throws() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        room.join(200L);
        room.start(999L);
        assertThrows(IllegalStateException.class, () -> room.start(998L));
    }

    @Test
    void finish_fromPlaying_success() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        room.join(200L);
        room.start(999L);
        room.finish();
        assertEquals(GameRoom.STATUS_FINISHED, room.getStatus());
        List<DomainEvent> events = room.pullEvents();
        assertTrue(events.stream().anyMatch(e -> e instanceof RoomFinishedEvent));
    }

    @Test
    void finish_fromWaiting_throws() {
        GameRoom room = newWaitingRoom();
        assertThrows(IllegalStateException.class, room::finish);
    }

    @Test
    void pullEvents_clearsEvents() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        room.join(200L);
        room.start(999L);
        assertFalse(room.pullEvents().isEmpty());
        assertTrue(room.pullEvents().isEmpty());
    }
}
