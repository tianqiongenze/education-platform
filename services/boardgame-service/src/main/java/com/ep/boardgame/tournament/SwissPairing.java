package com.ep.boardgame.tournament;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * D-192: Swiss-system pairing algorithm.
 * <p>
 * Rules implemented:
 * <ul>
 *   <li>Sort participants by score descending.</li>
 *   <li>Group by equal score, pair within the same score group.</li>
 *   <li>Avoid rematches (checked via {@code opponentHistory}).</li>
 *   <li>Balance colors: alternate RED/BLACK based on prior {@code colorPreference}.</li>
 *   <li>Odd participant count: lowest-score player receives a BYE (only one bye per player).</li>
 * </ul>
 * This is a deterministic, dependency-free implementation suitable for the
 * boardgame-service MVP. It is pure (no I/O) so it can be unit tested directly.
 */
public final class SwissPairing {

    private SwissPairing() {}

    /**
     * Pair the given participants for the specified round.
     *
     * @param participants all active participants (must be non-null, may be empty)
     * @param round        1-based round number (currently informational)
     * @return ordered list of pairings; the last pairing may be a BYE
     */
    public static List<Pairing> pair(List<TournamentParticipant> participants, int round) {
        if (participants == null || participants.isEmpty()) {
            return List.of();
        }

        // Work on a defensive copy sorted by score desc, then rating desc.
        List<TournamentParticipant> sorted = new ArrayList<>(participants);
        sorted.sort(Comparator
                .comparingDouble(TournamentParticipant::getScore).reversed()
                .thenComparing(Comparator
                        .comparingInt((TournamentParticipant p) ->
                                p.getSeedRating() == null ? 0 : p.getSeedRating()).reversed()));

        // Assign BYE if odd count: give the BYE to the lowest-score player who
        // has not already had a bye (tracked via opponent_history containing "BYE").
        List<TournamentParticipant> pool = new ArrayList<>(sorted);
        Pairing byePairing = null;
        if (pool.size() % 2 != 0) {
            TournamentParticipant byeRecipient = pickByeRecipient(pool);
            if (byeRecipient != null) {
                pool.remove(byeRecipient);
                byePairing = new Pairing(byeRecipient.getPlayerId(), null, null, null);
            }
        }

        // Greedy pairing with rematch avoidance.
        Set<Long> paired = new HashSet<>();
        List<Pairing> pairings = new ArrayList<>();
        for (TournamentParticipant p1 : pool) {
            if (paired.contains(p1.getPlayerId())) continue;
            TournamentParticipant chosen = null;
            for (TournamentParticipant p2 : pool) {
                if (p2.getPlayerId().equals(p1.getPlayerId())) continue;
                if (paired.contains(p2.getPlayerId())) continue;
                if (p1.hasFaced(p2.getPlayerId())) continue;
                chosen = p2;
                break;
            }
            // If no non-rematch opponent found, relax the rematch constraint
            // (rare for early rounds; required to avoid deadlock in late rounds).
            if (chosen == null) {
                for (TournamentParticipant p2 : pool) {
                    if (p2.getPlayerId().equals(p1.getPlayerId())) continue;
                    if (paired.contains(p2.getPlayerId())) continue;
                    chosen = p2;
                    break;
                }
            }
            if (chosen == null) {
                // Odd one out with no opponent — assign a BYE inline.
                pairings.add(new Pairing(p1.getPlayerId(), null, null, null));
                paired.add(p1.getPlayerId());
            } else {
                paired.add(p1.getPlayerId());
                paired.add(chosen.getPlayerId());
                String[] colors = assignColors(p1, chosen);
                pairings.add(new Pairing(p1.getPlayerId(), chosen.getPlayerId(),
                        colors[0], colors[1]));
            }
        }

        if (byePairing != null) {
            pairings.add(byePairing);
        }
        return pairings;
    }

    /**
     * Pick the lowest-score player who has not yet received a BYE.
     * We approximate "received a BYE" by checking the player has at least one
     * recorded opponent with id matching the BYE marker — but since opponent
     * history stores real playerIds, we instead use a simpler heuristic: BYE
     * recipients are not re-picked if they have an opponent_history entry
     * literally containing "BYE". To support that, callers should add the
     * literal string opponent "BYE" when recording a bye. For MVP we keep it
     * simple and just pick the lowest-score active player.
     */
    private static TournamentParticipant pickByeRecipient(List<TournamentParticipant> pool) {
        TournamentParticipant lowest = null;
        for (TournamentParticipant p : pool) {
            // Skip players who have already received a bye (encoded as opponent "BYE").
            if (p.getOpponentHistory() != null && p.getOpponentHistory().contains("BYE")) continue;
            if (lowest == null || (p.getScore() < lowest.getScore())) {
                lowest = p;
            }
        }
        return lowest;
    }

    /**
     * Assign colors so that the player who has fewer "preferred" colors recently
     * gets their preferred color. For MVP we alternate based on colorPreference:
     * if p1 prefers BALANCE_BLACK, p1 gets BLACK; else RED. p2 gets the opposite.
     */
    private static String[] assignColors(TournamentParticipant p1, TournamentParticipant p2) {
        String c1 = (p1.getColorPreference() == TournamentParticipant.ColorPreference.BALANCE_BLACK)
                ? "BLACK" : "RED";
        String c2 = c1.equals("RED") ? "BLACK" : "RED";
        return new String[]{c1, c2};
    }
}
