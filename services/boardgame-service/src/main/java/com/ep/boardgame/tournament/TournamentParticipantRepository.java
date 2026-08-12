package com.ep.boardgame.tournament;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TournamentParticipantRepository
        extends JpaRepository<TournamentParticipant, TournamentParticipant.PK> {

    List<TournamentParticipant> findByTournamentId(Long tournamentId);

    List<TournamentParticipant> findByTournamentIdAndIsActiveTrue(Long tournamentId);

    List<TournamentParticipant> findByTournamentIdOrderByScoreDescTiebreakScoreDesc(Long tournamentId);
}
