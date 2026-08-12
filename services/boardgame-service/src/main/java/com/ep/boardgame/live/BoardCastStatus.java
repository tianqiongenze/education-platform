package com.ep.boardgame.live;

import java.time.Instant;

/**
 * D-192: Status of a BOARD_CAST live broadcast for a game room.
 *
 * @param gameRoomId    the game room the broadcast is attached to
 * @param active        whether a broadcast is currently active
 * @param ingestUrl     the ingest endpoint (RTMP/SRT) the broadcaster pushes to
 * @param playUrl       the playback URL (HLL/HLS) viewers consume
 * @param startedAt     when the broadcast was started, or null
 */
public record BoardCastStatus(
        Long gameRoomId,
        boolean active,
        String ingestUrl,
        String playUrl,
        Instant startedAt
) {}
