package com.ep.data.nlquery;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for the NL Query Text-to-SQL module (ADR-D08, §12.4.1).
 *
 * <p>Exposes three operations:
 * <ul>
 *   <li>{@code POST /api/v1/nlquery} — run the Text-to-SQL pipeline for a
 *       single question.</li>
 *   <li>{@code GET /api/v1/nlquery/sessions/{sessionId}/history} — return
 *       the multi-turn conversation history for a session.</li>
 *   <li>{@code DELETE /api/v1/nlquery/sessions/{sessionId}} — clear a
 *       session and its history.</li>
 * </ul>
 *
 * <p>Follows the same controller pattern as {@code LineageController} /
 * {@code DataAssetProfileController}: constructor injection, SLF4J logger,
 * {@link ResponseEntity} responses, and an EP_DEV_ONLY guard on audit-log
 * access.
 */
@RestController
@RequestMapping("/api/v1/nlquery")
@Tag(name = "自然语言查询", description = "Text-to-SQL自然语言数据查询")
public class NlQueryController {

    private static final Logger log = LoggerFactory.getLogger(NlQueryController.class);

    private final TextToSqlService textToSqlService;
    private final ConversationContext conversationContext;

    public NlQueryController(TextToSqlService textToSqlService,
                             ConversationContext conversationContext) {
        this.textToSqlService = textToSqlService;
        this.conversationContext = conversationContext;
    }

    /**
     * Run the Text-to-SQL pipeline for a single question.
     *
     * @param cmd payload with user id, question, and session id
     * @return the query result
     */
    @PostMapping
    public ResponseEntity<NlQueryResult> query(@RequestBody QueryCmd cmd) {
        if (cmd == null || cmd.question() == null || cmd.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        NlQueryResult result = textToSqlService.query(cmd.userId(), cmd.question(), cmd.sessionId());
        log.info("[NlQueryController] query — intent={}, valid={}, latencyMs={}",
                result.intent(), result.isValid(), result.latencyMs());
        return ResponseEntity.ok(result);
    }

    /**
     * Return the multi-turn conversation history for a session.
     *
     * @param sessionId session id
     * @return list of chat turns (oldest first)
     */
    @GetMapping("/sessions/{sessionId}/history")
    public ResponseEntity<List<ChatTurn>> history(@PathVariable String sessionId) {
        return ResponseEntity.ok(conversationContext.getHistory(sessionId));
    }

    /**
     * Clear a session and its history.
     *
     * @param sessionId session id
     * @return 204 No Content
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        conversationContext.clearSession(sessionId);
        log.info("[NlQueryController] Cleared session {}", sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Return the dev/CI audit log (EP_DEV_ONLY guarded).
     *
     * <p>In production this endpoint is a no-op stub — the real audit log
     * lives in the {@code audit.nl_query_log} table.
     *
     * @return audit log entries (dev/CI) or empty list (production)
     */
    @GetMapping("/audit")
    public ResponseEntity<List<String>> auditLog() {
        String devOnly = System.getenv("EP_DEV_ONLY");
        boolean devGuard = devOnly == null || devOnly.isBlank() || "true".equalsIgnoreCase(devOnly);
        if (!devGuard) {
            log.info("[NlQueryController] EP_DEV_ONLY inactive — audit log access disabled in production");
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(textToSqlService.getAuditLog());
    }

    /** Payload for {@code POST /api/v1/nlquery}. */
    public record QueryCmd(String userId, String question, String sessionId) {
    }
}
