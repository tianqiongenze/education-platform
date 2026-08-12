package com.ep.grading.live;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * D-187: JPA repository for {@link LiveRoom}.
 */
public interface LiveRoomRepository extends JpaRepository<LiveRoom, Long> {

    Optional<LiveRoom> findByRoomId(Long roomId);
}
