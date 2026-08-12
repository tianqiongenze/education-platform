package com.ep.boardgame.social;

import com.ep.boardgame.room.GameMatch;
import com.ep.web.common.RoleChecker;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * D-192: Social/friends REST controller.
 */
@RestController
@RequestMapping("/api/v1/social/friends")
public class FriendController {

    private final FriendService friendService;

    public FriendController(FriendService friendService) {
        this.friendService = friendService;
    }

    @PostMapping
    public Friendship add(@RequestParam Long playerId,
                          @RequestParam Long friendId,
                          HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "super_admin");
        return friendService.addFriend(playerId, friendId);
    }

    @PostMapping("/{friendId}/accept")
    public Friendship accept(@PathVariable Long friendId,
                             @RequestParam Long playerId,
                             HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "super_admin");
        return friendService.acceptFriend(playerId, friendId);
    }

    @DeleteMapping("/{friendId}")
    public void remove(@PathVariable Long friendId,
                       @RequestParam Long playerId,
                       HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "super_admin");
        friendService.removeFriend(playerId, friendId);
    }

    @GetMapping
    public List<Friendship> list(@RequestParam Long playerId) {
        return friendService.listFriends(playerId);
    }

    @GetMapping("/mutual")
    public List<Long> mutual(@RequestParam Long playerId,
                             @RequestParam Long otherPlayerId) {
        return friendService.listMutualFriends(playerId, otherPlayerId);
    }

    @GetMapping("/matches")
    public List<GameMatch> matchHistory(@RequestParam Long playerId,
                                        @RequestParam Long otherPlayerId) {
        return friendService.getMatchHistory(playerId, otherPlayerId);
    }
}
