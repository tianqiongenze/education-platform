package com.ep.boardgame.tournament;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * D-192: TournamentServiceImpl unit tests.
 */
class TournamentServiceTest {

    private TournamentRepository tournamentRepo;
    private TournamentParticipantRepository participantRepo;
    private TournamentServiceImpl service;

    @BeforeEach
    void setUp() {
        tournamentRepo = mock(TournamentRepository.class);
        participantRepo = mock(TournamentParticipantRepository.class);
        when(tournamentRepo.save(any(Tournament.class))).thenAnswer(inv -> {
            Tournament t = inv.getArgument(0);
            if (t.getTournamentId() == null) t.setTournamentId(9001L);
            return t;
        });
        when(participantRepo.save(any(TournamentParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new TournamentServiceImpl(tournamentRepo, participantRepo);
    }

    private Tournament newTournament() {
        Tournament t = new Tournament();
        t.setName("Spring Cup");
        t.setGameType(Tournament.GameType.XIANGQI);
        t.setFormat(Tournament.Format.SWISS);
        t.setMaxParticipants(8);
        t.setTenantId(10L);
        return t;
    }

    @Test
    void create_success() {
        Tournament t = service.create(newTournament());
        assertNotNull(t.getTournamentId());
        assertEquals(Tournament.Status.SCHEDULED, t.getStatus());
        assertEquals(0, t.getCurrentRound());
        assertNotNull(t.getTotalRounds());
        assertTrue(t.getTotalRounds() >= 1);
    }

    @Test
    void create_defaultsSwiss() {
        Tournament t = newTournament();
        t.setFormat(null);
        Tournament saved = service.create(t);
        assertEquals(Tournament.Format.SWISS, saved.getFormat());
    }

    @Test
    void register_and_start() {
        Tournament t = service.create(newTournament());
        when(tournamentRepo.findById(t.getTournamentId())).thenReturn(Optional.of(t));
        when(participantRepo.findByTournamentId(t.getTournamentId()))
                .thenReturn(List.of(
                        participant(101L, t.getTournamentId()),
                        participant(102L, t.getTournamentId())));

        service.register(t.getTournamentId(), 101L, 1500);
        service.register(t.getTournamentId(), 102L, 1600);

        Tournament started = service.startTournament(t.getTournamentId());
        assertEquals(Tournament.Status.IN_PROGRESS, started.getStatus());
        assertEquals(1, started.getCurrentRound());
    }

    @Test
    void pairRound_returnsPairings() {
        Tournament t = service.create(newTournament());
        t.setStatus(Tournament.Status.IN_PROGRESS);
        t.setCurrentRound(1);
        when(tournamentRepo.findById(t.getTournamentId())).thenReturn(Optional.of(t));
        when(participantRepo.findByTournamentIdAndIsActiveTrue(t.getTournamentId()))
                .thenReturn(List.of(
                        participant(1L, t.getTournamentId()),
                        participant(2L, t.getTournamentId()),
                        participant(3L, t.getTournamentId()),
                        participant(4L, t.getTournamentId())));

        List<Pairing> pairings = service.pairRound(t.getTournamentId());
        assertEquals(2, pairings.size());
    }

    @Test
    void recordResult_updatesScores() {
        Long tid = 1L;
        TournamentParticipant p1 = participant(101L, tid);
        TournamentParticipant p2 = participant(102L, tid);
        when(tournamentRepo.findById(tid)).thenReturn(Optional.of(newTournament()));
        when(participantRepo.findById(new TournamentParticipant.PK(tid, 101L)))
                .thenReturn(Optional.of(p1));
        when(participantRepo.findById(new TournamentParticipant.PK(tid, 102L)))
                .thenReturn(Optional.of(p2));

        service.recordResult(tid, 101L, 102L, "WIN");
        assertEquals(1.0, p1.getScore());
        assertEquals(0.0, p2.getScore());
        assertTrue(p1.hasFaced(102L));
        assertTrue(p2.hasFaced(101L));
    }

    @Test
    void recordResult_bye() {
        Long tid = 1L;
        TournamentParticipant p1 = participant(101L, tid);
        when(tournamentRepo.findById(tid)).thenReturn(Optional.of(newTournament()));
        when(participantRepo.findById(new TournamentParticipant.PK(tid, 101L)))
                .thenReturn(Optional.of(p1));

        service.recordResult(tid, 101L, null, "BYE");
        assertEquals(1.0, p1.getScore());
    }

    @Test
    void completeTournament_setsCompleted() {
        Tournament t = service.create(newTournament());
        t.setStatus(Tournament.Status.IN_PROGRESS);
        when(tournamentRepo.findById(t.getTournamentId())).thenReturn(Optional.of(t));
        Tournament done = service.completeTournament(t.getTournamentId());
        assertEquals(Tournament.Status.COMPLETED, done.getStatus());
    }

    private TournamentParticipant participant(Long playerId, Long tid) {
        TournamentParticipant p = new TournamentParticipant();
        p.setTournamentId(tid);
        p.setPlayerId(playerId);
        p.setSeedRating(1500);
        p.setScore(0.0);
        p.setOpponentHistory("[]");
        p.setIsActive(true);
        return p;
    }
}
