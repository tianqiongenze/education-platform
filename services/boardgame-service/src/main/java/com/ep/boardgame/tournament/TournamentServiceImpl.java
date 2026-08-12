package com.ep.boardgame.tournament;

import com.ep.boardgame.common.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * D-192: Tournament service implementation.
 * <p>
 * Uses {@link SwissPairing} to pair rounds and {@link TournamentParticipant#recordResult}
 * to update scores (win=1.0, draw=0.5, loss=0.0, bye=1.0).
 */
@Service
public class TournamentServiceImpl implements TournamentService {

    private static final Logger log = LoggerFactory.getLogger(TournamentServiceImpl.class);

    private final TournamentRepository tournamentRepo;
    private final TournamentParticipantRepository participantRepo;

    public TournamentServiceImpl(TournamentRepository tournamentRepo,
                                 TournamentParticipantRepository participantRepo) {
        this.tournamentRepo = tournamentRepo;
        this.participantRepo = participantRepo;
    }

    @Override
    @Transactional
    public Tournament create(Tournament tournament) {
        if (tournament.getName() == null || tournament.getName().isBlank()) {
            throw new IllegalArgumentException("tournament name must not be blank");
        }
        if (tournament.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId required");
        }
        if (tournament.getFormat() == null) {
            tournament.setFormat(Tournament.Format.SWISS);
        }
        if (tournament.getGameType() == null) {
            throw new IllegalArgumentException("gameType required");
        }
        if (tournament.getMaxParticipants() == null || tournament.getMaxParticipants() < 2) {
            throw new IllegalArgumentException("maxParticipants must be >= 2");
        }
        if (tournament.getTotalRounds() == null || tournament.getTotalRounds() < 1) {
            // Default Swiss rounds: ceil(log2(n))
            int n = tournament.getMaxParticipants();
            tournament.setTotalRounds(Math.max(1, (int) Math.ceil(Math.log(n) / Math.log(2))));
        }
        tournament.setTournamentId(SnowflakeIdGenerator.getInstance().nextId());
        tournament.setStatus(Tournament.Status.SCHEDULED);
        tournament.setCurrentRound(0);
        long now = System.currentTimeMillis();
        tournament.setCreatedAt(now);
        tournament.setUpdatedAt(now);
        return tournamentRepo.save(tournament);
    }

    @Override
    @Transactional
    public TournamentParticipant register(Long tournamentId, Long playerId, Integer seedRating) {
        Tournament t = tournamentRepo.findById(tournamentId)
                .orElseThrow(() -> new IllegalStateException("Tournament not found: " + tournamentId));
        if (t.getStatus() != Tournament.Status.SCHEDULED) {
            throw new IllegalStateException("Cannot register after tournament started (status=" + t.getStatus() + ")");
        }
        long count = participantRepo.findByTournamentId(tournamentId).size();
        if (count >= t.getMaxParticipants()) {
            throw new IllegalStateException("Tournament is full");
        }
        TournamentParticipant p = new TournamentParticipant();
        p.setTournamentId(tournamentId);
        p.setPlayerId(playerId);
        p.setSeedRating(seedRating == null ? 1000 : seedRating);
        p.setScore(0.0d);
        p.setTiebreakScore(0.0d);
        p.setColorPreference(TournamentParticipant.ColorPreference.BALANCE_WHITE);
        p.setOpponentHistory("[]");
        p.setIsActive(true);
        return participantRepo.save(p);
    }

    @Override
    @Transactional
    public Tournament startTournament(Long tournamentId) {
        Tournament t = tournamentRepo.findById(tournamentId)
                .orElseThrow(() -> new IllegalStateException("Tournament not found: " + tournamentId));
        List<TournamentParticipant> parts = participantRepo.findByTournamentId(tournamentId);
        if (parts.size() < 2) {
            throw new IllegalStateException("Need at least 2 participants to start");
        }
        t.start();
        return tournamentRepo.save(t);
    }

    @Override
    @Transactional
    public List<Pairing> pairRound(Long tournamentId) {
        Tournament t = tournamentRepo.findById(tournamentId)
                .orElseThrow(() -> new IllegalStateException("Tournament not found: " + tournamentId));
        if (t.getStatus() != Tournament.Status.IN_PROGRESS) {
            throw new IllegalStateException("Tournament is not IN_PROGRESS");
        }
        List<TournamentParticipant> active =
                participantRepo.findByTournamentIdAndIsActiveTrue(tournamentId);
        List<Pairing> pairings = SwissPairing.pair(active, t.getCurrentRound());
        log.info("D-192: paired round {} of tournament {} -> {} pairings",
                t.getCurrentRound(), tournamentId, pairings.size());
        return pairings;
    }

    @Override
    @Transactional
    public void recordResult(Long tournamentId, Long player1Id, Long player2Id, String result) {
        Tournament t = tournamentRepo.findById(tournamentId)
                .orElseThrow(() -> new IllegalStateException("Tournament not found: " + tournamentId));
        TournamentParticipant p1 = participantRepo
                .findById(new TournamentParticipant.PK(tournamentId, player1Id))
                .orElseThrow(() -> new IllegalStateException("Participant not found: " + player1Id));

        if (player2Id == null) {
            // BYE
            if (!"BYE".equalsIgnoreCase(result) && !"WIN".equalsIgnoreCase(result)) {
                throw new IllegalArgumentException("BYE pairing result must be BYE or WIN");
            }
            p1.recordResult("BYE");
            p1.addOpponent(parseByeMarker());
            participantRepo.save(p1);
            return;
        }

        TournamentParticipant p2 = participantRepo
                .findById(new TournamentParticipant.PK(tournamentId, player2Id))
                .orElseThrow(() -> new IllegalStateException("Participant not found: " + player2Id));

        switch (result.toUpperCase()) {
            case "WIN" -> {
                p1.recordResult("WIN");
                p2.recordResult("LOSS");
            }
            case "LOSS" -> {
                p1.recordResult("LOSS");
                p2.recordResult("WIN");
            }
            case "DRAW" -> {
                p1.recordResult("DRAW");
                p2.recordResult("DRAW");
            }
            default -> throw new IllegalArgumentException("Unknown result: " + result);
        }
        p1.addOpponent(player2Id);
        p2.addOpponent(player1Id);
        participantRepo.save(p1);
        participantRepo.save(p2);
    }

    @Override
    @Transactional
    public Tournament completeTournament(Long tournamentId) {
        Tournament t = tournamentRepo.findById(tournamentId)
                .orElseThrow(() -> new IllegalStateException("Tournament not found: " + tournamentId));
        t.complete();
        return tournamentRepo.save(t);
    }

    @Override
    public List<TournamentParticipant> getStandings(Long tournamentId) {
        return participantRepo.findByTournamentIdOrderByScoreDescTiebreakScoreDesc(tournamentId);
    }

    @Override
    public Tournament getTournament(Long tournamentId) {
        return tournamentRepo.findById(tournamentId).orElse(null);
    }

    @Override
    public List<TournamentParticipant> getParticipants(Long tournamentId) {
        return new ArrayList<>(participantRepo.findByTournamentId(tournamentId));
    }

    @Override
    public List<Tournament> listByTenant(Long tenantId) {
        if (tenantId == null) return tournamentRepo.findAll();
        return tournamentRepo.findByTenantIdOrderByTournamentIdDesc(tenantId);
    }

    /** Marker used to indicate a BYE in opponent_history (avoids re-awarding byes). */
    private static Long parseByeMarker() {
        // Encode the BYE as a sentinel value that is unlikely to collide with real playerIds.
        return -1L;
    }
}
