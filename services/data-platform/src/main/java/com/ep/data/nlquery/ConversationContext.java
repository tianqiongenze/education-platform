package com.ep.data.nlquery;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * In-memory multi-turn conversation context for the NL Query module
 * (ADR-D08, §12.4.2).
 *
 * <p>Holds the last {@value #MAX_TURNS_PER_SESSION} turns per session in a
 * {@link ConcurrentHashMap} of {@link Deque}s (keyed by session id). Sessions
 * expire after {@value #SESSION_TTL_MINUTES} minutes of inactivity (TTL is
 * checked lazily on access).
 *
 * <p>Thread-safety (D-039 compliant): uses {@code volatile} for the mutable
 * per-session timestamp and {@link ConcurrentHashMap} for the session index —
 * no {@code synchronized} blocks. Per-session deque mutation is guarded by
 * the {@code compute} family on the {@link ConcurrentHashMap}, which provides
 * atomicity per key.
 *
 * <p>This is the dev/CI in-memory fallback (same pattern as
 * {@code LineageGraph} / {@code DataAssetProfileController}). Production
 * deployments persist turns to the Iceberg {@code audit.nl_query_log} table.
 */
@Component
public class ConversationContext {

    /** Maximum turns retained per session (§12.4.2). */
    public static final int MAX_TURNS_PER_SESSION = 5;

    /** Session inactivity TTL in minutes (§12.4.2). */
    public static final long SESSION_TTL_MINUTES = 30;

    private final Map<String, Deque<ChatTurn>> sessions = new ConcurrentHashMap<>();
    private final Map<String, VolatileTimestamp> lastAccess = new ConcurrentHashMap<>();

    /**
     * Add a turn to the session's history, trimming to the last
     * {@value #MAX_TURNS_PER_SESSION} turns.
     *
     * @param sessionId conversation session id
     * @param turn      turn to add
     */
    public void addTurn(String sessionId, ChatTurn turn) {
        if (sessionId == null || turn == null) {
            return;
        }
        sessions.compute(sessionId, (k, deque) -> {
            if (deque == null) {
                deque = new ArrayDeque<>();
            }
            deque.addLast(turn);
            while (deque.size() > MAX_TURNS_PER_SESSION) {
                deque.pollFirst();
            }
            return deque;
        });
        touch(sessionId);
    }

    /**
     * Return a copy of the session's turn history (oldest first). Returns an
     * empty list for unknown/expired sessions.
     *
     * @param sessionId conversation session id
     * @return copy of the turn history (never null)
     */
    public List<ChatTurn> getHistory(String sessionId) {
        if (sessionId == null) {
            return new ArrayList<>();
        }
        if (isExpired(sessionId)) {
            clearSession(sessionId);
            return new ArrayList<>();
        }
        touch(sessionId);
        Deque<ChatTurn> deque = sessions.get(sessionId);
        if (deque == null) {
            return new ArrayList<>();
        }
        // Snapshot under compute to avoid concurrent modification.
        return sessions.compute(sessionId, (k, d) -> {
            // no-op; we just need an atomic read
            return d;
        }).stream().toList();
    }

    /**
     * Remove a session and its history.
     *
     * @param sessionId conversation session id
     */
    public void clearSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        sessions.remove(sessionId);
        lastAccess.remove(sessionId);
    }

    /**
     * Build a follow-up SQL from the session's most recent turn and the new
     * question. This is an MVP placeholder that performs simple text
     * manipulation (§12.4.2):
     * <ul>
     *   <li>If the last turn exists and the new question contains "加",
     *       "filter", or "where" → append {@code AND 1=1} to the last SQL.</li>
     *   <li>If the new question contains "group" or "维度" → replace an
     *       existing {@code GROUP BY} clause or append {@code GROUP BY 1}.</li>
     *   <li>Otherwise return {@code null} (no follow-up; fresh generation needed).</li>
     * </ul>
     *
     * @param sessionId    conversation session id
     * @param newQuestion  the new natural-language question
     * @return modified SQL, or {@code null} when no follow-up applies
     */
    public String buildFollowupSql(String sessionId, String newQuestion) {
        if (sessionId == null || newQuestion == null) {
            return null;
        }
        if (isExpired(sessionId)) {
            clearSession(sessionId);
            return null;
        }
        Deque<ChatTurn> deque = sessions.get(sessionId);
        if (deque == null || deque.isEmpty()) {
            return null;
        }
        ChatTurn last = deque.peekLast();
        if (last == null || last.sql() == null || last.sql().isBlank()) {
            return null;
        }
        String baseSql = last.sql();
        String lowerQuestion = newQuestion.toLowerCase();

        if (newQuestion.contains("加") || lowerQuestion.contains("filter") || lowerQuestion.contains("where")) {
            return baseSql + " AND 1=1";
        }
        if (lowerQuestion.contains("group") || newQuestion.contains("维度")) {
            int gbIdx = baseSql.toUpperCase().indexOf("GROUP BY");
            if (gbIdx >= 0) {
                // Replace existing GROUP BY clause up to the next clause keyword.
                int endIdx = findClauseEnd(baseSql, gbIdx);
                return baseSql.substring(0, gbIdx) + "GROUP BY 1" + baseSql.substring(endIdx);
            }
            return baseSql + " GROUP BY 1";
        }
        return null;
    }

    /**
     * Check whether a session has expired due to inactivity.
     *
     * @param sessionId conversation session id
     * @return true if the session exists but its TTL has elapsed
     */
    private boolean isExpired(String sessionId) {
        VolatileTimestamp ts = lastAccess.get(sessionId);
        if (ts == null) {
            return true;
        }
        Duration age = Duration.between(ts.value, LocalDateTime.now());
        return age.toMinutes() >= SESSION_TTL_MINUTES;
    }

    private void touch(String sessionId) {
        lastAccess.put(sessionId, new VolatileTimestamp(LocalDateTime.now()));
    }

    /** Find the end index of a GROUP BY clause (start of next clause keyword or end of string). */
    private static int findClauseEnd(String sql, int fromIdx) {
        String upper = sql.toUpperCase();
        String[] nextClauses = {"HAVING", "ORDER BY", "LIMIT", "UNION", "EXCEPT", "INTERSECT", "WINDOW"};
        int minIdx = sql.length();
        for (String clause : nextClauses) {
            int idx = upper.indexOf(clause, fromIdx + 8);
            if (idx >= 0 && idx < minIdx) {
                minIdx = idx;
            }
        }
        return minIdx;
    }

    /** Volatile holder for a per-session last-access timestamp (D-039). */
    private static final class VolatileTimestamp {
        volatile LocalDateTime value;

        VolatileTimestamp(LocalDateTime value) {
            this.value = value;
        }
    }
}
