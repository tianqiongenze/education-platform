package com.ep.grading.live;

import com.ep.grading.live.dto.CreateLiveRoomCmd;
import com.ep.grading.live.impl.LiveRoomServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-187: Unit tests for {@link LiveRoomServiceImpl}. Repository is mocked so
 * tests run without TiDB/H2. State-machine transitions are exercised via the
 * service's lifecycle methods.
 */
class LiveRoomServiceTest {

    private LiveRoomRepository liveRoomRepository;
    private LiveRoomServiceImpl service;
    private final AtomicLong idSeq = new AtomicLong(0L);

    @BeforeEach
    void setUp() {
        liveRoomRepository = mock(LiveRoomRepository.class);
        when(liveRoomRepository.save(any(LiveRoom.class))).thenAnswer(inv -> {
            LiveRoom r = inv.getArgument(0);
            if (r.getRoomId() == null) {
                r.setRoomId(idSeq.incrementAndGet());
            }
            return r;
        });
        service = new LiveRoomServiceImpl(liveRoomRepository);
    }

    private LiveRoom createRoom() {
        return service.create(new CreateLiveRoomCmd(1L, 10L, "SRS", 500));
    }

    @Test
    void create_assigns_id_and_created_status() {
        LiveRoom room = createRoom();
        assertNotNull(room.getRoomId());
        assertEquals(LiveRoom.Status.CREATED, room.getStatus());
        assertEquals(LiveRoom.Mode.SRS, room.getMode());
        assertEquals(500, room.getViewerLimit());
    }

    @Test
    void create_defaults_viewer_limit() {
        LiveRoom room = service.create(new CreateLiveRoomCmd(1L, 10L, "SFU", null));
        assertEquals(1000, room.getViewerLimit());
    }

    @Test
    void lifecycle_happy_path() {
        LiveRoom room = createRoom();
        when(liveRoomRepository.findByRoomId(room.getRoomId())).thenReturn(Optional.of(room));

        room = service.prepare(room.getRoomId());
        assertEquals(LiveRoom.Status.PREPARING, room.getStatus());

        room = service.goLive(room.getRoomId());
        assertEquals(LiveRoom.Status.LIVE, room.getStatus());

        room = service.pause(room.getRoomId());
        assertEquals(LiveRoom.Status.PAUSED, room.getStatus());

        room = service.resume(room.getRoomId());
        assertEquals(LiveRoom.Status.LIVE, room.getStatus());

        room = service.end(room.getRoomId());
        assertEquals(LiveRoom.Status.ENDED, room.getStatus());

        room = service.archive(room.getRoomId());
        assertEquals(LiveRoom.Status.ARCHIVED, room.getStatus());
    }

    @Test
    void prepare_from_live_is_illegal() {
        LiveRoom room = createRoom();
        when(liveRoomRepository.findByRoomId(room.getRoomId())).thenReturn(Optional.of(room));
        service.prepare(room.getRoomId()); // CREATED -> PREPARING
        service.goLive(room.getRoomId());  // PREPARING -> LIVE
        // prepare() from LIVE must throw.
        assertThrows(IllegalStateException.class, () -> service.prepare(room.getRoomId()));
    }

    @Test
    void end_from_created_is_illegal() {
        LiveRoom room = createRoom();
        when(liveRoomRepository.findByRoomId(room.getRoomId())).thenReturn(Optional.of(room));
        assertThrows(IllegalStateException.class, () -> service.end(room.getRoomId()));
    }

    @Test
    void archive_from_live_is_illegal() {
        LiveRoom room = createRoom();
        when(liveRoomRepository.findByRoomId(room.getRoomId())).thenReturn(Optional.of(room));
        service.prepare(room.getRoomId());
        service.goLive(room.getRoomId());
        assertThrows(IllegalStateException.class, () -> service.archive(room.getRoomId()));
    }

    @Test
    void findById_returns_null_when_missing() {
        when(liveRoomRepository.findByRoomId(999L)).thenReturn(Optional.empty());
        assertEquals(null, service.findById(999L));
    }

    @Test
    void create_rejects_null_tenant() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new CreateLiveRoomCmd(null, 10L, "SRS", 100)));
    }

    @Test
    void create_rejects_unknown_mode() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new CreateLiveRoomCmd(1L, 10L, "weird", 100)));
    }
}
