package com.ep.boardgame.live;

import com.ep.boardgame.room.GameRoom;
import com.ep.boardgame.room.GameRoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * D-192: BoardCastService unit tests.
 */
class BoardCastServiceTest {

    private GameRoomRepository roomRepo;
    private LiveGoClient liveGoClient;
    private BoardCastService service;

    @BeforeEach
    void setUp() {
        roomRepo = mock(GameRoomRepository.class);
        liveGoClient = mock(LiveGoClient.class);
        when(roomRepo.save(any(GameRoom.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new BoardCastService(roomRepo, liveGoClient);
    }

    private GameRoom room(Long id) {
        GameRoom r = new GameRoom();
        r.setRoomId(id);
        r.setTenantId(10L);
        r.setStatus(GameRoom.STATUS_PLAYING);
        return r;
    }

    @Test
    void startBoardCast_liveGoAvailable_persistsUrls() {
        GameRoom r = room(1L);
        when(roomRepo.findById(1L)).thenReturn(Optional.of(r));
        when(liveGoClient.createBroadcastRoom(1L))
                .thenReturn(new LiveGoClient.LiveRoomInfo("live-1", "rtmp://ingest/x", "https://play/x.m3u8"));

        BoardCastStatus status = service.startBoardCast(1L);

        assertTrue(status.active());
        assertEquals("rtmp://ingest/x", status.ingestUrl());
        assertEquals("https://play/x.m3u8", status.playUrl());
        assertEquals("rtmp://ingest/x", r.getBoardcastIngestUrl());
    }

    @Test
    void startBoardCast_liveGoUnavailable_usesPlaceholder() {
        GameRoom r = room(1L);
        when(roomRepo.findById(1L)).thenReturn(Optional.of(r));
        when(liveGoClient.createBroadcastRoom(1L)).thenReturn(null);

        BoardCastStatus status = service.startBoardCast(1L);

        assertTrue(status.active());
        assertNotNull(status.ingestUrl());
        assertNotNull(status.playUrl());
        assertTrue(status.playUrl().contains("placeholder"));
    }

    @Test
    void startBoardCast_alreadyActive_isIdempotent() {
        GameRoom r = room(1L);
        r.setBoardcastIngestUrl("rtmp://existing");
        r.setBoardcastPlayUrl("https://existing/x.m3u8");
        when(roomRepo.findById(1L)).thenReturn(Optional.of(r));

        BoardCastStatus status = service.startBoardCast(1L);
        assertTrue(status.active());
        assertEquals("https://existing/x.m3u8", status.playUrl());
        verify(liveGoClient, never()).createBroadcastRoom(any());
    }

    @Test
    void stopBoardCast_clearsUrls() {
        GameRoom r = room(1L);
        r.setBoardcastIngestUrl("rtmp://x");
        r.setBoardcastPlayUrl("https://x.m3u8");
        when(roomRepo.findById(1L)).thenReturn(Optional.of(r));
        when(liveGoClient.stopBroadcastRoom(1L)).thenReturn(true);

        BoardCastStatus status = service.stopBoardCast(1L);

        assertFalse(status.active());
        assertNull(r.getBoardcastIngestUrl());
        assertNull(r.getBoardcastPlayUrl());
    }

    @Test
    void getBoardCastStatus_notFound_inactive() {
        when(roomRepo.findById(99L)).thenReturn(Optional.empty());
        BoardCastStatus status = service.getBoardCastStatus(99L);
        assertFalse(status.active());
    }
}
