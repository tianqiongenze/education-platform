package com.ep.grading.live.controller;

import com.ep.grading.live.LiveRoom;
import com.ep.grading.live.dto.CreateLiveRoomCmd;
import com.ep.grading.live.service.LiveRoomService;
import com.ep.web.common.RoleChecker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D-187: REST controller for live room lifecycle.
 * <p>
 * Teachers/admins create and drive the room state machine; the actual media
 * plane (WHIP/WHEP, SFU token) is served by the live-go service.
 */
@RestController
@RequestMapping("/api/v1/live/rooms")
@Validated
public class LiveRoomController {

    @DubboReference
    private LiveRoomService liveRoomService;

    @PostMapping
    public ResponseEntity<LiveRoom> create(@Valid @RequestBody CreateLiveRoomCmd cmd,
                                           HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        LiveRoom room = liveRoomService.create(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(room);
    }

    @PostMapping("/{roomId}/prepare")
    public ResponseEntity<LiveRoom> prepare(@PathVariable Long roomId,
                                            HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        return ResponseEntity.ok(liveRoomService.prepare(roomId));
    }

    @PostMapping("/{roomId}/live")
    public ResponseEntity<LiveRoom> goLive(@PathVariable Long roomId,
                                           HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        return ResponseEntity.ok(liveRoomService.goLive(roomId));
    }

    @PostMapping("/{roomId}/pause")
    public ResponseEntity<LiveRoom> pause(@PathVariable Long roomId,
                                          HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        return ResponseEntity.ok(liveRoomService.pause(roomId));
    }

    @PostMapping("/{roomId}/resume")
    public ResponseEntity<LiveRoom> resume(@PathVariable Long roomId,
                                           HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        return ResponseEntity.ok(liveRoomService.resume(roomId));
    }

    @PostMapping("/{roomId}/end")
    public ResponseEntity<LiveRoom> end(@PathVariable Long roomId,
                                        HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        return ResponseEntity.ok(liveRoomService.end(roomId));
    }

    @PostMapping("/{roomId}/archive")
    public ResponseEntity<LiveRoom> archive(@PathVariable Long roomId,
                                            HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        return ResponseEntity.ok(liveRoomService.archive(roomId));
    }

    @PostMapping("/{roomId}")
    public ResponseEntity<LiveRoom> findById(@PathVariable Long roomId) {
        LiveRoom room = liveRoomService.findById(roomId);
        if (room == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(room);
    }
}
