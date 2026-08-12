package com.ep.boardgame.room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GameMatchRepository extends JpaRepository<GameMatch, Long> {

    List<GameMatch> findByRoomIdOrderByMatchIdDesc(Long roomId);

    List<GameMatch> findByRedPlayerIdOrBlackPlayerIdOrderByMatchIdDesc(Long redPlayerId, Long blackPlayerId);

    List<GameMatch> findByTenantIdOrderByMatchIdDesc(Long tenantId);
}
