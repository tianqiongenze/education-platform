package com.ep.boardgame.room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GameRoomRepository extends JpaRepository<GameRoom, Long> {

    List<GameRoom> findAllByOrderByRoomIdDesc();

    List<GameRoom> findByTenantIdOrderByRoomIdDesc(Long tenantId);

    List<GameRoom> findByStatusAndTenantId(Integer status, Long tenantId);

    List<GameRoom> findByCourseIdOrderByRoomIdDesc(Long courseId);
}
