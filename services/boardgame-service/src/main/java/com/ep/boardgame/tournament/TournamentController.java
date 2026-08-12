package com.ep.boardgame.tournament;

import com.ep.web.common.RoleChecker;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * D-192: Tournament REST controller.
 * <p>
 * Role model: students can create/join tournaments; teachers and admins
 * have full access including starting, pairing, recording results and
 * completing a tournament.
 */
@RestController
@RequestMapping("/api/v1/tournaments")
public class TournamentController {

    private final TournamentService tournamentService;

    public TournamentController(TournamentService tournamentService) {
        this.tournamentService = tournamentService;
    }

    @PostMapping
    public Tournament create(@RequestBody CreateTournamentCmd cmd, HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "super_admin");
        Tournament t = new Tournament();
        t.setName(cmd.name());
        t.setGameType(cmd.gameType());
        t.setFormat(cmd.format() == null ? Tournament.Format.SWISS : cmd.format());
        t.setMaxParticipants(cmd.maxParticipants());
        t.setTotalRounds(cmd.totalRounds());
        t.setTenantId(cmd.tenantId());
        t.setStartDate(cmd.startDate() == null ? Instant.now() : cmd.startDate());
        return tournamentService.create(t);
    }

    @GetMapping("/{tournamentId}")
    public Tournament get(@PathVariable Long tournamentId) {
        return tournamentService.getTournament(tournamentId);
    }

    @GetMapping
    public List<Tournament> list(@RequestParam(required = false) Long tenantId) {
        return tournamentService.listByTenant(tenantId);
    }

    @PostMapping("/{tournamentId}/register")
    public TournamentParticipant register(@PathVariable Long tournamentId,
                                          @RequestParam Long playerId,
                                          @RequestParam(required = false) Integer seedRating,
                                          HttpServletRequest request) {
        RoleChecker.requireRole(request, "student", "teacher", "admin", "super_admin");
        return tournamentService.register(tournamentId, playerId, seedRating);
    }

    @PostMapping("/{tournamentId}/start")
    public Tournament start(@PathVariable Long tournamentId, HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        return tournamentService.startTournament(tournamentId);
    }

    @PostMapping("/{tournamentId}/pair")
    public List<Pairing> pairRound(@PathVariable Long tournamentId, HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        return tournamentService.pairRound(tournamentId);
    }

    @PostMapping("/{tournamentId}/results")
    public ResponseEntity<Void> recordResult(@PathVariable Long tournamentId,
                                              @RequestBody RecordResultCmd cmd,
                                              HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        tournamentService.recordResult(tournamentId, cmd.player1Id(),
                cmd.player2Id(), cmd.result());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{tournamentId}/standings")
    public List<TournamentParticipant> standings(@PathVariable Long tournamentId) {
        return tournamentService.getStandings(tournamentId);
    }

    @GetMapping("/{tournamentId}/participants")
    public List<TournamentParticipant> participants(@PathVariable Long tournamentId) {
        return tournamentService.getParticipants(tournamentId);
    }

    @PostMapping("/{tournamentId}/complete")
    public Tournament complete(@PathVariable Long tournamentId, HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        return tournamentService.completeTournament(tournamentId);
    }

    /** Command body for tournament creation. */
    public record CreateTournamentCmd(
            String name,
            Tournament.GameType gameType,
            Tournament.Format format,
            Integer maxParticipants,
            Integer totalRounds,
            Long tenantId,
            Instant startDate
    ) {}

    /** Command body for recording a pairing result. */
    public record RecordResultCmd(
            Long player1Id,
            Long player2Id, // null for BYE
            String result    // WIN, LOSS, DRAW, BYE
    ) {}
}
