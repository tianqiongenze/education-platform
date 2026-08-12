package com.ep.grading.ide.service;

import com.ep.grading.ide.IdeSession;
import com.ep.grading.ide.dto.CreateIdeSessionCmd;

import java.util.Optional;

/**
 * D-186: Domain service for managing IDE sessions.
 * <p>
 * Owns persistence and state transitions for {@link IdeSession}. The Yjs
 * document itself is coordinated by the ws-gateway (binary WebSocket protocol);
 * this service exposes the REST lifecycle and the {@link #touch(String)} /
 * {@link #checkIdle(String)} hooks the gateway calls on each update.
 */
public interface IdeSessionService {

    IdeSession create(CreateIdeSessionCmd cmd);

    IdeSession activate(String sessionId);

    IdeSession suspend(String sessionId);

    IdeSession close(String sessionId);

    /** Refresh {@code lastActiveAt} — called by the ws-gateway on every Yjs update. */
    IdeSession touch(String sessionId);

    /** Flip to IDLE if the idle timeout has elapsed; returns the (possibly updated) session. */
    IdeSession checkIdle(String sessionId);

    Optional<IdeSession> findBySessionId(String sessionId);

    Optional<IdeSession> findByYjsDocId(String yjsDocId);
}
