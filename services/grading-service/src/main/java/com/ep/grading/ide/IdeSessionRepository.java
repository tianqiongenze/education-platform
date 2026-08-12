package com.ep.grading.ide;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * D-186: JPA repository for {@link IdeSession}.
 */
public interface IdeSessionRepository extends JpaRepository<IdeSession, String> {

    Optional<IdeSession> findBySessionId(String sessionId);

    Optional<IdeSession> findByYjsDocId(String yjsDocId);
}
