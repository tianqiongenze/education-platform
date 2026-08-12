package com.ep.boardgame.replay;

import com.ep.web.common.RoleChecker;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * D-149: Game replay REST controller.
 * Replays are used as learning materials in the Match-as-Lesson pattern.
 */
@RestController
@RequestMapping("/api/v1/replays")
public class GameReplayController {

    private final GameReplayService replayService;

    public GameReplayController(GameReplayService replayService) {
        this.replayService = replayService;
    }

    @GetMapping("/{matchId}")
    public GameReplay getReplay(@PathVariable Long matchId) {
        return replayService.getReplayByMatch(matchId);
    }

    @GetMapping
    public List<GameReplay> listReplays(@RequestParam(required = false) Long tenantId,
                                        @RequestParam(required = false) Long courseId,
                                        HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "super_admin");
        if (courseId != null) return replayService.listReplaysByCourse(courseId);
        return replayService.listReplaysByTenant(tenantId);
    }
}
