package com.ep.boardgame.live;

import com.ep.boardgame.room.GameRoom;
import com.ep.boardgame.room.GameRoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * D-192: BOARD_CAST live broadcast service.
 * <p>
 * Starts/stops a live broadcast for a game room by delegating the actual
 * media pipeline to the live-go service (BROADCAST mode). The ingest and
 * playback URLs are persisted on {@link GameRoom} so spectators can fetch
 * them later. EP_DEV_ONLY: when live-go is unreachable, a placeholder URL is
 * returned and a warning is logged — the service stays functional.
 */
@Service
public class BoardCastService {

    private static final Logger log = LoggerFactory.getLogger(BoardCastService.class);

    private final GameRoomRepository roomRepo;
    private final LiveGoClient liveGoClient;

    public BoardCastService(GameRoomRepository roomRepo, LiveGoClient liveGoClient) {
        this.roomRepo = roomRepo;
        this.liveGoClient = liveGoClient;
    }

    @Transactional
    public BoardCastStatus startBoardCast(Long gameRoomId) {
        GameRoom room = roomRepo.findById(gameRoomId)
                .orElseThrow(() -> new IllegalStateException("Room not found: " + gameRoomId));
        if (room.getBoardcastPlayUrl() != null) {
            log.info("D-192: boardcast already active for room {}", gameRoomId);
            return new BoardCastStatus(gameRoomId, true,
                    room.getBoardcastIngestUrl(), room.getBoardcastPlayUrl(), Instant.now());
        }
        LiveGoClient.LiveRoomInfo info = liveGoClient.createBroadcastRoom(gameRoomId);
        String ingest;
        String play;
        if (info != null) {
            ingest = info.ingestUrl();
            play = info.playUrl();
        } else {
            log.warn("D-192: live-go unavailable (EP_DEV_ONLY), using placeholder URLs for room {}", gameRoomId);
            ingest = "rtmp://placeholder/live/" + gameRoomId;
            play = "https://placeholder/live/" + gameRoomId + ".m3u8";
        }
        room.setBoardcastIngestUrl(ingest);
        room.setBoardcastPlayUrl(play);
        roomRepo.save(room);
        return new BoardCastStatus(gameRoomId, true, ingest, play, Instant.now());
    }

    @Transactional
    public BoardCastStatus stopBoardCast(Long gameRoomId) {
        GameRoom room = roomRepo.findById(gameRoomId)
                .orElseThrow(() -> new IllegalStateException("Room not found: " + gameRoomId));
        liveGoClient.stopBroadcastRoom(gameRoomId);
        room.setBoardcastIngestUrl(null);
        room.setBoardcastPlayUrl(null);
        roomRepo.save(room);
        return new BoardCastStatus(gameRoomId, false, null, null, null);
    }

    public BoardCastStatus getBoardCastStatus(Long gameRoomId) {
        Optional<GameRoom> opt = roomRepo.findById(gameRoomId);
        if (opt.isEmpty()) {
            return new BoardCastStatus(gameRoomId, false, null, null, null);
        }
        GameRoom room = opt.get();
        boolean active = room.getBoardcastPlayUrl() != null;
        return new BoardCastStatus(gameRoomId, active,
                room.getBoardcastIngestUrl(), room.getBoardcastPlayUrl(), null);
    }
}
