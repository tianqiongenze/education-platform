package com.ep.grading.ide.impl;

import com.ep.grading.common.SnowflakeIdGenerator;
import com.ep.grading.ide.IdeSession;
import com.ep.grading.ide.IdeSessionRepository;
import com.ep.grading.ide.dto.CreateIdeSessionCmd;
import com.ep.grading.ide.service.IdeSessionService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * D-186: {@link IdeSessionService} implementation.
 * <p>
 * Constructor injection (per project convention), Snowflake-generated session
 * ids, and JPA persistence. State transitions are delegated to the
 * {@link IdeSession} aggregate's state-machine methods.
 * <p>
 * Iron Law 1 (session state externalisation): the session record is the durable
 * truth; the in-memory Yjs document in ws-gateway is a cache rebuilt from
 * snapshots on reconnect.
 */
@DubboService
public class IdeSessionServiceImpl implements IdeSessionService {

    private final IdeSessionRepository repository;

    public IdeSessionServiceImpl(IdeSessionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public IdeSession create(CreateIdeSessionCmd cmd) {
        Objects.requireNonNull(cmd, "CreateIdeSessionCmd cannot be null");
        if (cmd.tenantId() == null) {
            throw new IllegalArgumentException("tenantId cannot be null");
        }
        if (cmd.userId() == null) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        IdeSession.Mode mode = parseMode(cmd.mode());
        int idleTimeout = cmd.idleTimeoutMin() != null && cmd.idleTimeoutMin() > 0
                ? cmd.idleTimeoutMin()
                : IdeSession.DEFAULT_IDLE_TIMEOUT_MIN;

        LocalDateTime now = LocalDateTime.now();
        String sessionId = String.valueOf(SnowflakeIdGenerator.getInstance().nextId());
        IdeSession session = new IdeSession(
                sessionId,
                cmd.tenantId(),
                cmd.userId(),
                mode,
                sessionId, // yjsDocId == sessionId for simplicity
                cmd.courseId(),
                cmd.lessonId(),
                idleTimeout,
                now);
        return repository.save(session);
    }

    @Override
    @Transactional
    public IdeSession activate(String sessionId) {
        IdeSession session = requireSession(sessionId);
        session.activate();
        return repository.save(session);
    }

    @Override
    @Transactional
    public IdeSession suspend(String sessionId) {
        IdeSession session = requireSession(sessionId);
        session.suspend();
        return repository.save(session);
    }

    @Override
    @Transactional
    public IdeSession close(String sessionId) {
        IdeSession session = requireSession(sessionId);
        session.close();
        return repository.save(session);
    }

    @Override
    @Transactional
    public IdeSession touch(String sessionId) {
        IdeSession session = requireSession(sessionId);
        session.touch();
        return repository.save(session);
    }

    @Override
    @Transactional
    public IdeSession checkIdle(String sessionId) {
        IdeSession session = requireSession(sessionId);
        if (session.checkIdle()) {
            return repository.save(session);
        }
        return session;
    }

    @Override
    public Optional<IdeSession> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return repository.findBySessionId(sessionId);
    }

    @Override
    public Optional<IdeSession> findByYjsDocId(String yjsDocId) {
        if (yjsDocId == null || yjsDocId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByYjsDocId(yjsDocId);
    }

    private IdeSession requireSession(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        return repository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("IdeSession not found: " + sessionId));
    }

    private IdeSession.Mode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return IdeSession.Mode.TEACHING;
        }
        try {
            return IdeSession.Mode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown IDE session mode: " + mode);
        }
    }
}
