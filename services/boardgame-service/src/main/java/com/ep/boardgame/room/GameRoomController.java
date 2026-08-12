package com.ep.boardgame.room;

import com.ep.web.common.RoleChecker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * D-149: Game room + match REST controller.
 * <p>
 * Role model: students can create/join rooms and make moves; teachers and
 * admins have full access. Replays are read-only for all authenticated users.
 */
@RestController
@RequestMapping("/api/v1/game")
@Validated
public class GameRoomController {

    private final GameRoomService roomService;

    public GameRoomController(GameRoomService roomService) {
        this.roomService = roomService;
    }

    // --- Room endpoints ---

    @PostMapping("/rooms")
    public GameRoom createRoom(@Valid @RequestBody CreateRoomCmd cmd, HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "super_admin");
        return roomService.createRoom(cmd);
    }

    @GetMapping("/rooms/{roomId}")
    public GameRoom getRoom(@PathVariable Long roomId) {
        return roomService.getRoomById(roomId);
    }

    @GetMapping("/rooms")
    public List<GameRoom> listRooms(@RequestParam(required = false) Long tenantId,
                                    @RequestParam(required = false) String status) {
        if ("waiting".equalsIgnoreCase(status)) {
            return roomService.listWaitingRooms(tenantId);
        }
        return roomService.listRooms(tenantId);
    }

    @PostMapping("/rooms/{roomId}/join")
    public GameRoom joinRoom(@PathVariable Long roomId,
                             @RequestParam Long playerId,
                             HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "super_admin");
        return roomService.joinRoom(roomId, playerId);
    }

    @PostMapping("/rooms/{roomId}/leave")
    public GameRoom leaveRoom(@PathVariable Long roomId,
                              @RequestParam Long playerId,
                              HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "super_admin");
        return roomService.leaveRoom(roomId, playerId);
    }

    @PostMapping("/rooms/{roomId}/start")
    public GameMatch startMatch(@PathVariable Long roomId, HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "super_admin");
        return roomService.startMatch(roomId);
    }

    // --- Match endpoints ---

    @GetMapping("/matches/{matchId}")
    public GameMatch getMatch(@PathVariable Long matchId) {
        return roomService.getMatchById(matchId);
    }

    @PostMapping("/matches/{matchId}/move")
    public GameMatch makeMove(@PathVariable Long matchId,
                              @Valid @RequestBody MakeMoveCmd cmd,
                              HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "super_admin");
        cmd.setMatchId(matchId);
        return roomService.makeMove(cmd);
    }

    @PostMapping("/matches/{matchId}/resign")
    public GameMatch resign(@PathVariable Long matchId,
                            @RequestParam Long playerId,
                            HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "super_admin");
        return roomService.resign(matchId, playerId);
    }

    @GetMapping("/matches")
    public List<GameMatch> listMatches(@RequestParam(required = false) Long playerId,
                                       @RequestParam(required = false) Long tenantId) {
        if (playerId != null) return roomService.listMatchesByPlayer(playerId);
        return roomService.listMatchesByTenant(tenantId);
    }
}
