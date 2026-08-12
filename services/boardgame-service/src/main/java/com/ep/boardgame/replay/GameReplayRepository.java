package com.ep.boardgame.replay;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GameReplayRepository extends JpaRepository<GameReplay, Long> {

    List<GameReplay> findByMatchId(Long matchId);

    List<GameReplay> findByTenantIdOrderByReplayIdDesc(Long tenantId);

    List<GameReplay> findByCourseIdOrderByReplayIdDesc(Long courseId);
}
