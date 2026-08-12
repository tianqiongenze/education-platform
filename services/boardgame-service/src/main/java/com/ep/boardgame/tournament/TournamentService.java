package com.ep.boardgame.tournament;

import java.util.List;

/**
 * D-192: Tournament service contract.
 */
public interface TournamentService {

    Tournament create(Tournament tournament);

    TournamentParticipant register(Long tournamentId, Long playerId, Integer seedRating);

    Tournament startTournament(Long tournamentId);

    /** Pair the current round. Returns the list of pairings. */
    List<Pairing> pairRound(Long tournamentId);

    /**
     * Record a result for a pairing in the current round.
     * @param tournamentId tournament id
     * @param player1Id first player of the pairing
     * @param player2Id second player of the pairing (null for a BYE)
     * @param result    "WIN"  = player1 wins; "LOSS" = player2 wins; "DRAW"; "BYE"
     */
    void recordResult(Long tournamentId, Long player1Id, Long player2Id, String result);

    /** Standings ordered by score desc, tiebreak desc. */
    List<TournamentParticipant> getStandings(Long tournamentId);

    Tournament completeTournament(Long tournamentId);

    Tournament getTournament(Long tournamentId);

    List<Tournament> listByTenant(Long tenantId);

    List<TournamentParticipant> getParticipants(Long tournamentId);
}
