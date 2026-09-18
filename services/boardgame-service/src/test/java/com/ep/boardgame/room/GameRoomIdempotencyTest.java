package com.ep.boardgame.room;

import com.ep.boardgame.event.PulsarEventPublisher;
import com.ep.cache.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-221: idempotent room creation — a retried create with the same business
 * key returns the original room instead of duplicating it.
 */
class GameRoomIdempotencyTest {

    private GameRoomRepository roomRepo;
    private GameMatchRepository matchRepo;
    private CacheService cacheService;
    private GameRoomServiceImpl service;

    @BeforeEach
    void setUp() {
        roomRepo = mock(GameRoomRepository.class);
        matchRepo = mock(GameMatchRepository.class);
        cacheService = mock(CacheService.class);
        when(roomRepo.save(any(GameRoom.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new GameRoomServiceImpl(roomRepo, matchRepo, noopPublisher(), cacheService);
    }

    private static PulsarEventPublisher noopPublisher() {
        ObjectProvider<org.apache.pulsar.client.api.PulsarClient> cp =
                mock(ObjectProvider.class);
        when(cp.getIfAvailable()).thenReturn(null);
        com.ep.boardgame.event.PulsarClientProperties props =
                new com.ep.boardgame.event.PulsarClientProperties();
        PulsarEventPublisher pub = new PulsarEventPublisher(cp, props, false);
        pub.init();
        return pub;
    }

    private CreateRoomCmd cmd(String key) {
        CreateRoomCmd cmd = new CreateRoomCmd();
        cmd.setTenantId(7L);
        cmd.setCourseId(1L);
        cmd.setRoomName("Alpha Room");
        cmd.setIdempotencyKey(key);
        return cmd;
    }

    @Test
    @DisplayName("first create with a key saves a room that carries the key")
    void firstCreatePersistsKey() {
        GameRoom room = service.createRoom(cmd("idem-1"));

        assertEquals("idem-1", room.getIdempotencyKey());
        verify(roomRepo, times(1)).save(any(GameRoom.class));
    }

    @Test
    @DisplayName("second create with the same key returns the original room without saving")
    void replayReturnsOriginal() {
        GameRoom original = new GameRoom();
        original.setRoomId(42L);
        original.setTenantId(7L);
        original.setStatus(GameRoom.STATUS_WAITING);
        when(roomRepo.findByTenantIdAndIdempotencyKey(7L, "idem-1"))
                .thenReturn(Optional.of(original));

        GameRoom result = service.createRoom(cmd("idem-1"));

        assertSame(original, result);
        assertEquals(42L, result.getRoomId());
        verify(roomRepo, never()).save(any(GameRoom.class));
    }

    @Test
    @DisplayName("different keys create different rooms")
    void differentKeysCreateSeparately() {
        when(roomRepo.findByTenantIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        GameRoom first = service.createRoom(cmd("idem-a"));
        GameRoom second = service.createRoom(cmd("idem-b"));

        verify(roomRepo, times(2)).save(any(GameRoom.class));
        assertEquals("idem-a", first.getIdempotencyKey());
        assertEquals("idem-b", second.getIdempotencyKey());
    }

    @Test
    @DisplayName("null or blank key keeps legacy create-always behaviour (no probe)")
    void nullKeyKeepsLegacyBehaviour() {
        service.createRoom(cmd(null));

        CreateRoomCmd blank = cmd("   ");
        GameRoom room = service.createRoom(blank);

        verify(roomRepo, never()).findByTenantIdAndIdempotencyKey(anyLong(), anyString());
        verify(roomRepo, times(2)).save(any(GameRoom.class));
        assertNull(room.getIdempotencyKey() == null ? null : room.getIdempotencyKey());
    }
}
