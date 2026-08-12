package com.ep.grading.ide;

import com.ep.grading.ide.dto.CreateIdeSessionCmd;
import com.ep.grading.ide.impl.IdeSessionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-186: IdeSessionService tests — repository mocked, verifies the state
 * machine transitions, idle detection, and create/validation logic.
 */
class IdeSessionServiceTest {

    private IdeSessionRepository repository;
    private IdeSessionServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(IdeSessionRepository.class);
        // save echoes the entity back
        when(repository.save(any(IdeSession.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new IdeSessionServiceImpl(repository);
    }

    @Test
    void create_persists_session_with_snowflake_id() {
        when(repository.findBySessionId(anyString())).thenReturn(Optional.empty());
        IdeSession session = service.create(
                new CreateIdeSessionCmd(1L, 5L, "TEACHING", 10L, 20L, 45));
        assertNotNull(session.getSessionId());
        assertEquals(1L, session.getTenantId());
        assertEquals(5L, session.getUserId());
        assertEquals(IdeSession.Mode.TEACHING, session.getMode());
        assertEquals(IdeSession.Status.CREATING, session.getStatus());
        assertEquals(45, session.getIdleTimeoutMin());
        assertEquals(session.getSessionId(), session.getYjsDocId());
    }

    @Test
    void create_defaults_mode_to_teaching_and_timeout_to_30() {
        IdeSession session = service.create(
                new CreateIdeSessionCmd(1L, 5L, null, null, null, null));
        assertEquals(IdeSession.Mode.TEACHING, session.getMode());
        assertEquals(IdeSession.DEFAULT_IDLE_TIMEOUT_MIN, session.getIdleTimeoutMin());
    }

    @Test
    void create_rejects_null_tenant_id() {
        assertThrows(IllegalArgumentException.class, () ->
                service.create(new CreateIdeSessionCmd(null, 5L, "TEACHING", null, null, 30)));
    }

    @Test
    void create_rejects_null_user_id() {
        assertThrows(IllegalArgumentException.class, () ->
                service.create(new CreateIdeSessionCmd(1L, null, "TEACHING", null, null, 30)));
    }

    @Test
    void create_rejects_unknown_mode() {
        assertThrows(IllegalArgumentException.class, () ->
                service.create(new CreateIdeSessionCmd(1L, 5L, "SANDBOX", null, null, 30)));
    }

    @Test
    void activate_transitions_creating_to_active() {
        IdeSession session = makeSession(IdeSession.Status.CREATING);
        when(repository.findBySessionId("s1")).thenReturn(Optional.of(session));

        IdeSession result = service.activate("s1");

        assertEquals(IdeSession.Status.ACTIVE, result.getStatus());
        assertNotNull(result.getLastActiveAt());
    }

    @Test
    void suspend_transitions_active_to_suspended() {
        IdeSession session = makeSession(IdeSession.Status.ACTIVE);
        when(repository.findBySessionId("s2")).thenReturn(Optional.of(session));
        assertEquals(IdeSession.Status.SUSPENDED, service.suspend("s2").getStatus());
    }

    @Test
    void close_from_active_sets_closed_at() {
        IdeSession session = makeSession(IdeSession.Status.ACTIVE);
        when(repository.findBySessionId("s3")).thenReturn(Optional.of(session));
        IdeSession closed = service.close("s3");
        assertEquals(IdeSession.Status.CLOSED, closed.getStatus());
        assertNotNull(closed.getClosedAt());
    }

    @Test
    void close_from_idle_allowed() {
        IdeSession session = makeSession(IdeSession.Status.IDLE);
        when(repository.findBySessionId("s4")).thenReturn(Optional.of(session));
        assertEquals(IdeSession.Status.CLOSED, service.close("s4").getStatus());
    }

    @Test
    void illegal_transition_throws() {
        IdeSession session = makeSession(IdeSession.Status.CLOSED);
        when(repository.findBySessionId("s5")).thenReturn(Optional.of(session));
        assertThrows(IllegalStateException.class, () -> service.activate("s5"));
    }

    @Test
    void touch_refreshes_last_active_at() {
        IdeSession session = makeSession(IdeSession.Status.ACTIVE);
        session.setLastActiveAt(LocalDateTime.now().minusMinutes(10));
        when(repository.findBySessionId("s6")).thenReturn(Optional.of(session));
        LocalDateTime before = session.getLastActiveAt();
        IdeSession touched = service.touch("s6");
        assertTrue(touched.getLastActiveAt().isAfter(before));
    }

    @Test
    void check_idle_flips_active_to_idle_when_timeout_elapsed() {
        IdeSession session = makeSession(IdeSession.Status.ACTIVE);
        session.setLastActiveAt(LocalDateTime.now().minusMinutes(61));
        when(repository.findBySessionId("s7")).thenReturn(Optional.of(session));
        IdeSession result = service.checkIdle("s7");
        assertEquals(IdeSession.Status.IDLE, result.getStatus());
    }

    @Test
    void check_idle_no_op_when_within_timeout() {
        IdeSession session = makeSession(IdeSession.Status.ACTIVE);
        session.setLastActiveAt(LocalDateTime.now().minusMinutes(5));
        when(repository.findBySessionId("s8")).thenReturn(Optional.of(session));
        IdeSession result = service.checkIdle("s8");
        assertEquals(IdeSession.Status.ACTIVE, result.getStatus());
    }

    @Test
    void check_idle_no_op_when_already_idle() {
        IdeSession session = makeSession(IdeSession.Status.IDLE);
        when(repository.findBySessionId("s9")).thenReturn(Optional.of(session));
        IdeSession result = service.checkIdle("s9");
        assertEquals(IdeSession.Status.IDLE, result.getStatus());
    }

    @Test
    void find_by_session_id_returns_empty_for_blank() {
        assertTrue(service.findBySessionId("").isEmpty());
        assertTrue(service.findBySessionId(null).isEmpty());
    }

    @Test
    void find_by_yjs_doc_id_delegates_to_repository() {
        IdeSession session = makeSession(IdeSession.Status.ACTIVE);
        when(repository.findByYjsDocId("doc-1")).thenReturn(Optional.of(session));
        Optional<IdeSession> found = service.findByYjsDocId("doc-1");
        assertTrue(found.isPresent());
        assertEquals("test-session", found.get().getSessionId());
    }

    @Test
    void require_session_throws_when_not_found() {
        when(repository.findBySessionId("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.activate("missing"));
    }

    @Test
    void can_transition_matrix_is_consistent() {
        // CREATING
        IdeSession creating = makeSession(IdeSession.Status.CREATING);
        assertTrue(creating.canTransitionTo(IdeSession.Status.ACTIVE));
        assertTrue(creating.canTransitionTo(IdeSession.Status.CLOSED));
        assertFalse(creating.canTransitionTo(IdeSession.Status.SUSPENDED));
        // ACTIVE
        IdeSession active = makeSession(IdeSession.Status.ACTIVE);
        assertTrue(active.canTransitionTo(IdeSession.Status.SUSPENDED));
        assertTrue(active.canTransitionTo(IdeSession.Status.IDLE));
        assertTrue(active.canTransitionTo(IdeSession.Status.CLOSED));
        assertFalse(active.canTransitionTo(IdeSession.Status.ACTIVE));
        // CLOSED is terminal
        IdeSession closed = makeSession(IdeSession.Status.CLOSED);
        assertFalse(closed.canTransitionTo(IdeSession.Status.ACTIVE));
        assertFalse(closed.canTransitionTo(IdeSession.Status.IDLE));
    }

    // --- helpers -------------------------------------------------------------

    private static IdeSession makeSession(IdeSession.Status status) {
        LocalDateTime now = LocalDateTime.now();
        IdeSession s = new IdeSession(
                "test-session", 1L, 5L, IdeSession.Mode.TEACHING,
                "test-session", 10L, 20L, 30, now);
        s.setStatus(status);
        s.setLastActiveAt(now);
        return s;
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
