package com.ep.grading.live.impl;

import com.ep.grading.common.SnowflakeIdGenerator;
import com.ep.grading.live.LiveRoom;
import com.ep.grading.live.LiveRoomRepository;
import com.ep.grading.live.dto.CreateLiveRoomCmd;
import com.ep.grading.live.service.LiveRoomService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * D-187: {@link LiveRoomService} implementation.
 * <p>
 * Uses constructor injection (per project convention) and Snowflake IDs.
 * State transitions are delegated to the {@link LiveRoom} aggregate's
 * state-machine methods, which enforce legal transitions.
 */
@DubboService
public class LiveRoomServiceImpl implements LiveRoomService {

    private final LiveRoomRepository liveRoomRepository;

    public LiveRoomServiceImpl(LiveRoomRepository liveRoomRepository) {
        this.liveRoomRepository = liveRoomRepository;
    }

    @Override
    @Transactional
    public LiveRoom create(CreateLiveRoomCmd cmd) {
        Objects.requireNonNull(cmd, "CreateLiveRoomCmd cannot be null");
        if (cmd.tenantId() == null) {
            throw new IllegalArgumentException("tenantId cannot be null");
        }
        if (cmd.userId() == null) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        LiveRoom.Mode mode = parseMode(cmd.mode());
        if (cmd.viewerLimit() < 1 || cmd.viewerLimit() > 100000) {
            throw new IllegalArgumentException("viewerLimit must be between 1 and 100000");
        }

        LocalDateTime now = LocalDateTime.now();
        LiveRoom room = new LiveRoom(
                SnowflakeIdGenerator.getInstance().nextId(),
                cmd.tenantId(),
                cmd.userId(),
                mode,
                cmd.viewerLimit(),
                now);
        return liveRoomRepository.save(room);
    }

    @Override
    @Transactional
    public LiveRoom prepare(Long roomId) {
        LiveRoom room = requireRoom(roomId);
        room.prepare();
        return liveRoomRepository.save(room);
    }

    @Override
    @Transactional
    public LiveRoom goLive(Long roomId) {
        LiveRoom room = requireRoom(roomId);
        room.goLive();
        return liveRoomRepository.save(room);
    }

    @Override
    @Transactional
    public LiveRoom pause(Long roomId) {
        LiveRoom room = requireRoom(roomId);
        room.pause();
        return liveRoomRepository.save(room);
    }

    @Override
    @Transactional
    public LiveRoom resume(Long roomId) {
        LiveRoom room = requireRoom(roomId);
        room.resume();
        return liveRoomRepository.save(room);
    }

    @Override
    @Transactional
    public LiveRoom end(Long roomId) {
        LiveRoom room = requireRoom(roomId);
        room.end();
        return liveRoomRepository.save(room);
    }

    @Override
    @Transactional
    public LiveRoom archive(Long roomId) {
        LiveRoom room = requireRoom(roomId);
        room.archive();
        return liveRoomRepository.save(room);
    }

    @Override
    public LiveRoom findById(Long roomId) {
        return liveRoomRepository.findByRoomId(roomId).orElse(null);
    }

    private LiveRoom requireRoom(Long roomId) {
        Objects.requireNonNull(roomId, "roomId cannot be null");
        return liveRoomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new IllegalArgumentException("LiveRoom not found: " + roomId));
    }

    private LiveRoom.Mode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return LiveRoom.Mode.SRS;
        }
        try {
            return LiveRoom.Mode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }
}
