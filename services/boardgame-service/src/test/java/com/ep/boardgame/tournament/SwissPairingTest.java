package com.ep.boardgame.tournament;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-192: SwissPairing algorithm unit tests.
 */
class SwissPairingTest {

    private TournamentParticipant p(Long id, double score, Integer rating) {
        TournamentParticipant p = new TournamentParticipant();
        p.setTournamentId(1L);
        p.setPlayerId(id);
        p.setSeedRating(rating);
        p.setScore(score);
        p.setOpponentHistory("[]");
        p.setColorPreference(TournamentParticipant.ColorPreference.BALANCE_WHITE);
        return p;
    }

    @Test
    void pairEightPlayers_allPaired_noRematch() {
        List<TournamentParticipant> players = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            players.add(p(i, 0.0, 1500));
        }
        List<Pairing> pairings = SwissPairing.pair(players, 1);
        assertEquals(4, pairings.size(), "8 players -> 4 pairings");
        assertTrue(pairings.stream().noneMatch(Pairing::isBye), "no bye for even count");

        // Round 2: simulate everyone has faced their r1 opponent.
        for (Pairing pr : pairings) {
            markFaced(players, pr.player1Id(), pr.player2Id());
            // Half win half loss for variety
            players.stream().filter(x -> x.getPlayerId().equals(pr.player1Id()))
                    .findFirst().ifPresent(x -> x.setScore(x.getScore() + 1.0));
        }
        List<Pairing> r2 = SwissPairing.pair(players, 2);
        assertEquals(4, r2.size());
        // Verify no rematch
        for (Pairing pr : r2) {
            TournamentParticipant p1 = find(players, pr.player1Id());
            if (pr.player2Id() != null) {
                assertFalse(p1.hasFaced(pr.player2Id()),
                        "rematch between " + pr.player1Id() + " and " + pr.player2Id());
            }
        }
    }

    @Test
    void pairOddCount_assignsBye() {
        List<TournamentParticipant> players = new ArrayList<>();
        for (long i = 1; i <= 7; i++) {
            players.add(p(i, 0.0, 1500));
        }
        List<Pairing> pairings = SwissPairing.pair(players, 1);
        assertEquals(4, pairings.size(), "7 players -> 3 pairings + 1 bye = 4 entries");
        long byes = pairings.stream().filter(Pairing::isBye).count();
        assertEquals(1, byes, "exactly one bye");
    }

    @Test
    void pair_assignsColors() {
        List<TournamentParticipant> players = new ArrayList<>();
        for (long i = 1; i <= 4; i++) {
            players.add(p(i, 0.0, 1500));
        }
        List<Pairing> pairings = SwissPairing.pair(players, 1);
        for (Pairing pr : pairings) {
            assertNotNull(pr.player1Color(), "color assigned to p1");
            assertNotNull(pr.player2Color(), "color assigned to p2");
            assertNotEquals(pr.player1Color(), pr.player2Color(),
                    "players in a pairing get opposite colors");
        }
    }

    @Test
    void pair_scoreGroups_pairWithinSameScore() {
        List<TournamentParticipant> players = new ArrayList<>();
        // Two players with score=2, two with score=0
        players.add(p(1L, 2.0, 1500));
        players.add(p(2L, 2.0, 1500));
        players.add(p(3L, 0.0, 1500));
        players.add(p(4L, 0.0, 1500));
        List<Pairing> pairings = SwissPairing.pair(players, 2);
        assertEquals(2, pairings.size());
        // Verify score-2 players are paired together
        Pairing top = pairings.stream()
                .filter(pr -> pr.player1Id() == 1L || pr.player1Id() == 2L
                        || pr.player2Id() == 1L || pr.player2Id() == 2L)
                .findFirst().orElseThrow();
        assertTrue((top.player1Id() == 1L || top.player1Id() == 2L)
                && (top.player2Id() == 1L || top.player2Id() == 2L));
    }

    @Test
    void pair_emptyInput_returnsEmpty() {
        assertTrue(SwissPairing.pair(List.of(), 1).isEmpty());
        assertTrue(SwissPairing.pair(null, 1).isEmpty());
    }

    private void markFaced(List<TournamentParticipant> players, Long a, Long b) {
        find(players, a).addOpponent(b);
        find(players, b).addOpponent(a);
    }

    private TournamentParticipant find(List<TournamentParticipant> players, Long id) {
        return players.stream().filter(x -> x.getPlayerId().equals(id))
                .findFirst().orElseThrow();
    }
}
