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

    private GameRoom newReadyRoom() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        room.join(200L); // auto WAITING → READY
        return room;
    }

    @Test
    void join_firstPlayer_becomesRed() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        assertEquals(100L, room.getRedPlayerId());
        assertNull(room.getBlackPlayerId());
        assertEquals(GameRoom.STATUS_WAITING, room.getStatus());
    }

    @Test
    void join_secondPlayer_becomesBlack_andAutoReady() {
        GameRoom room = newWaitingRoom();
        room.join(100L);
        room.join(200L);
        assertEquals(100L, room.getRedPlayerId());
        assertEquals(200L, room.getBlackPlayerId());
        assertEquals(GameRoom.STATUS_READY, room.getStatus());
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
        GameRoom room = newReadyRoom();
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
    void leave_readyRoom_revertsToWaiting() {
        GameRoom room = newReadyRoom();
        room.leave(200L);
        assertEquals(GameRoom.STATUS_WAITING, room.getStatus());
    }

    @Test
    void leave_playingRoom_throws() {
        GameRoom room = newReadyRoom();
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
        GameRoom room = newReadyRoom();
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
    void start_notReady_throws() {
        GameRoom room = newReadyRoom();
        room.start(999L);
        assertThrows(IllegalStateException.class, () -> room.start(998L));
    }

    @Test
    void finish_fullTransition_success() {
        GameRoom room = newReadyRoom();
        room.start(999L);
        room.beginFinish();
        assertEquals(GameRoom.STATUS_FINISHING, room.getStatus());
        room.finish();
        assertEquals(GameRoom.STATUS_FINISHED, room.getStatus());
        List<DomainEvent> events = room.pullEvents();
        assertTrue(events.stream().anyMatch(e -> e instanceof RoomFinishedEvent));
    }

    @Test
    void finish_fromPlaying_throws() {
        GameRoom room = newReadyRoom();
        room.start(999L);
        assertThrows(IllegalStateException.class, room::finish);
    }

    @Test
    void finish_fromWaiting_throws() {
        GameRoom room = newWaitingRoom();
        assertThrows(IllegalStateException.class, room::finish);
    }

    @Test
    void canTransitionTo_validPaths() {
        GameRoom room = newWaitingRoom();
        assertTrue(room.canTransitionTo(GameRoom.STATUS_READY));
        assertFalse(room.canTransitionTo(GameRoom.STATUS_PLAYING));
        assertFalse(room.canTransitionTo(GameRoom.STATUS_WAITING));
        assertTrue(room.canTransitionTo(GameRoom.STATUS_ABORTED));
    }

    @Test
    void canTransitionTo_terminalStates() {
        GameRoom room = newReadyRoom();
        room.start(1L);
        room.beginFinish();
        room.finish();
        assertEquals(GameRoom.STATUS_FINISHED, room.getStatus());
        assertFalse(room.canTransitionTo(GameRoom.STATUS_ABORTED));
        assertFalse(room.canTransitionTo(GameRoom.STATUS_PLAYING));
    }

    @Test
    void abort_fromPlaying_success() {
        GameRoom room = newReadyRoom();
        room.start(1L);
        room.abort();
        assertEquals(GameRoom.STATUS_ABORTED, room.getStatus());
    }

    @Test
    void abort_fromAborted_throws() {
        GameRoom room = newWaitingRoom();
        room.abort();
        assertThrows(IllegalStateException.class, room::abort);
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
