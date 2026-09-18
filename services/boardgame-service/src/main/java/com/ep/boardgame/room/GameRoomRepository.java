package com.ep.boardgame.room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameRoomRepository extends JpaRepository<GameRoom, Long> {

    List<GameRoom> findAllByOrderByRoomIdDesc();

    List<GameRoom> findByTenantIdOrderByRoomIdDesc(Long tenantId);

    List<GameRoom> findByStatusAndTenantId(Integer status, Long tenantId);

    List<GameRoom> findByCourseIdOrderByRoomIdDesc(Long courseId);

    /** D-221: probe for an existing room created with the same idempotency key. */
    Optional<GameRoom> findByTenantIdAndIdempotencyKey(Long tenantId, String idempotencyKey);
}
