package com.ep.boardgame.live;

import com.ep.web.common.RoleChecker;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D-192: BOARD_CAST REST controller.
 */
@RestController
@RequestMapping("/api/v1/game/rooms/{roomId}/boardcast")
public class BoardCastController {

    private final BoardCastService boardCastService;

    public BoardCastController(BoardCastService boardCastService) {
        this.boardCastService = boardCastService;
    }

    @PostMapping("/start")
    public BoardCastStatus start(@PathVariable Long roomId, HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        return boardCastService.startBoardCast(roomId);
    }

    @PostMapping("/stop")
    public BoardCastStatus stop(@PathVariable Long roomId, HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        return boardCastService.stopBoardCast(roomId);
    }

    @GetMapping
    public BoardCastStatus status(@PathVariable Long roomId) {
        return boardCastService.getBoardCastStatus(roomId);
    }
}
