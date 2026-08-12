package com.ep.boardgame.tournament;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TournamentRepository extends JpaRepository<Tournament, Long> {

    List<Tournament> findByTenantIdOrderByTournamentIdDesc(Long tenantId);

    List<Tournament> findByStatus(Tournament.Status status);
}
