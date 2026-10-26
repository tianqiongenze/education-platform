package com.ep.grading.ide.controller;

import com.ep.grading.ide.IdeSession;
import com.ep.grading.ide.dto.CreateIdeSessionCmd;
import com.ep.grading.ide.service.IdeSessionService;
import com.ep.web.common.RoleChecker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * D-186: REST controller for IDE session lifecycle.
 * <p>
 * Teachers/admins create and drive the session state machine. Students connect
 * to the Yjs WebSocket ({@code /ws/ide/{sessionId}}) served by ws-gateway for
 * real-time collaboration; this controller owns the REST side.
 */
@RestController
@RequestMapping("/api/v1/ide/sessions")
@Validated
public class IdeSessionController {

    // D-232: self-referencing Dubbo within one app is an anti-pattern and
    // breaks in registry=N/A mode — use the local bean directly.
    @org.springframework.beans.factory.annotation.Autowired
    private IdeSessionService ideSessionService;

    @PostMapping
    public ResponseEntity<IdeSession> create(@Valid @RequestBody CreateIdeSessionCmd cmd,
                                             HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        IdeSession session = ideSessionService.create(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @PostMapping("/{sessionId}/activate")
    public ResponseEntity<IdeSession> activate(@PathVariable String sessionId,
                                               HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin", "student");
        return ResponseEntity.ok(ideSessionService.activate(sessionId));
    }

    @PostMapping("/{sessionId}/suspend")
    public ResponseEntity<IdeSession> suspend(@PathVariable String sessionId,
                                              HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        return ResponseEntity.ok(ideSessionService.suspend(sessionId));
    }

    @PostMapping("/{sessionId}/close")
    public ResponseEntity<IdeSession> close(@PathVariable String sessionId,
                                            HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin");
        return ResponseEntity.ok(ideSessionService.close(sessionId));
    }

    @PostMapping("/{sessionId}/touch")
    public ResponseEntity<IdeSession> touch(@PathVariable String sessionId,
                                            HttpServletRequest request) {
        RoleChecker.requireRole(request, "teacher", "admin", "super_admin", "student");
        return ResponseEntity.ok(ideSessionService.touch(sessionId));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<IdeSession> findById(@PathVariable String sessionId) {
        Optional<IdeSession> session = ideSessionService.findBySessionId(sessionId);
        return session.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
