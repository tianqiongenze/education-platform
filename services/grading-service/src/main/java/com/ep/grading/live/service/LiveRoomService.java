package com.ep.grading.live.service;

import com.ep.grading.live.LiveRoom;
import com.ep.grading.live.dto.CreateLiveRoomCmd;

/**
 * D-187: Domain service for managing live rooms.
 * <p>
 * Coordinates the LiveRoom state machine with the live-go backend
 * (ingest/play URLs, SFU room id). The orchestration of the live-go REST API
 * is performed by the controller via Dubbo/HTTP; this service owns persistence
 * and state transitions.
 */
public interface LiveRoomService {

    LiveRoom create(CreateLiveRoomCmd cmd);

    LiveRoom prepare(Long roomId);

    LiveRoom goLive(Long roomId);

    LiveRoom pause(Long roomId);

    LiveRoom resume(Long roomId);

    LiveRoom end(Long roomId);

    LiveRoom archive(Long roomId);

    LiveRoom findById(Long roomId);
}
