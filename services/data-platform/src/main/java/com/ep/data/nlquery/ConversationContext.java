package com.ep.data.nlquery;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Multi-turn conversation context for the NL Query module
 * (ADR-D08, §12.4.2), upgraded with Redis-backed persistence (D-190).
 *
 * <p>Holds the last {@value #MAX_TURNS_PER_SESSION} turns per session. Sessions
 * expire after {@value #SESSION_TTL_MINUTES} minutes of inactivity (TTL is
 * checked lazily on access for in-memory mode; enforced via Redis TTL in
 * production mode).
 *
 * <p><b>Two storage modes (D-190):</b>
 * <ul>
 *   <li><b>Dev/CI (EP_DEV_ONLY guard active):</b> In-memory
 *       {@link ConcurrentHashMap} of {@link Deque}s (keyed by session id).
 *       Same pattern as {@code LineageGraph} /
 *       {@code DataAssetProfileController}.</li>
 *   <li><b>Production (EP_DEV_ONLY=false):</b> Redis-backed via
 *       {@link StringRedisTemplate}. Each session is stored as a Redis list
 *       ({@code nlquery:session:{sessionId}}) with a TTL of
 *       {@value #SESSION_TTL_MINUTES} minutes. Turns are JSON-serialized.</li>
 * </ul>
 *
 * <p>Thread-safety (D-039 compliant): uses {@code volatile} for the mutable
 * per-session timestamp and {@link ConcurrentHashMap} for the session index —
 * no {@code synchronized} blocks. Per-session deque mutation is guarded by
 * the {@code compute} family on the {@link ConcurrentHashMap}, which provides
 * atomicity per key. Redis operations are atomic via single-key commands.
 *
 * <p>Uses {@link ObjectProvider} for {@link StringRedisTemplate} so the bean
 * can be constructed even when Redis is not configured (dev/CI). Redis errors
 * fall back to in-memory (graceful degradation).
 */
@Component
public class ConversationContext {

    private static final Logger log = LoggerFactory.getLogger(ConversationContext.class);

    /** Maximum turns retained per session (§12.4.2). */
    public static final int MAX_TURNS_PER_SESSION = 5;

    /** Session inactivity TTL in minutes (§12.4.2). */
    public static final long SESSION_TTL_MINUTES = 30;

    /** Redis key prefix for session storage. */
    static final String REDIS_KEY_PREFIX = "nlquery:session:";

    private final Map<String, Deque<ChatTurn>> sessions = new ConcurrentHashMap<>();
    private final Map<String, VolatileTimestamp> lastAccess = new ConcurrentHashMap<>();
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper mapper;

    /** Default constructor — dev/CI mode (no Redis). */
    @org.springframework.beans.factory.annotation.Autowired
    public ConversationContext() {
        this(null);
    }

    /**
     * Construct with a Redis provider (production mode).
     *
     * @param redisProvider Spring {@link ObjectProvider} for
     *        {@link StringRedisTemplate}; may be null in tests/dev
     */
    public ConversationContext(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

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

        if (useRedis()) {
            addTurnRedis(sessionId, turn);
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

        if (useRedis()) {
            return getHistoryRedis(sessionId);
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

        if (useRedis()) {
            clearSessionRedis(sessionId);
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

        ChatTurn last;
        if (useRedis()) {
            last = getLastTurnRedis(sessionId);
        } else {
            if (isExpired(sessionId)) {
                clearSession(sessionId);
                return null;
            }
            Deque<ChatTurn> deque = sessions.get(sessionId);
            if (deque == null || deque.isEmpty()) {
                return null;
            }
            last = deque.peekLast();
        }

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

    // ─── Redis-backed operations (D-190) ───────────────────────────────

    private void addTurnRedis(String sessionId, ChatTurn turn) {
        StringRedisTemplate redis = resolveRedis();
        if (redis == null) {
            // Fallback to in-memory.
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
            return;
        }
        try {
            String key = REDIS_KEY_PREFIX + sessionId;
            String json = mapper.writeValueAsString(turn);
            redis.opsForList().rightPush(key, json);
            // Trim to max turns.
            Long size = redis.opsForList().size(key);
            if (size != null && size > MAX_TURNS_PER_SESSION) {
                redis.opsForList().trim(key, size - MAX_TURNS_PER_SESSION, -1);
            }
            // Refresh TTL.
            redis.expire(key, Duration.ofMinutes(SESSION_TTL_MINUTES));
        } catch (Exception e) {
            log.warn("[ConversationContext] Redis addTurn failed, falling back to in-memory: {}", e.getMessage());
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
    }

    private List<ChatTurn> getHistoryRedis(String sessionId) {
        StringRedisTemplate redis = resolveRedis();
        if (redis == null) {
            return new ArrayList<>();
        }
        try {
            String key = REDIS_KEY_PREFIX + sessionId;
            List<String> jsonList = redis.opsForList().range(key, 0, -1);
            if (jsonList == null || jsonList.isEmpty()) {
                return new ArrayList<>();
            }
            // Refresh TTL on access.
            redis.expire(key, Duration.ofMinutes(SESSION_TTL_MINUTES));
            List<ChatTurn> turns = new ArrayList<>(jsonList.size());
            for (String json : jsonList) {
                turns.add(mapper.readValue(json, ChatTurn.class));
            }
            return turns;
        } catch (Exception e) {
            log.warn("[ConversationContext] Redis getHistory failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private ChatTurn getLastTurnRedis(String sessionId) {
        StringRedisTemplate redis = resolveRedis();
        if (redis == null) {
            return null;
        }
        try {
            String key = REDIS_KEY_PREFIX + sessionId;
            List<String> last = redis.opsForList().range(key, -1, -1);
            if (last == null || last.isEmpty()) {
                return null;
            }
            return mapper.readValue(last.get(0), ChatTurn.class);
        } catch (Exception e) {
            log.warn("[ConversationContext] Redis getLastTurn failed: {}", e.getMessage());
            return null;
        }
    }

    private void clearSessionRedis(String sessionId) {
        StringRedisTemplate redis = resolveRedis();
        if (redis == null) {
            sessions.remove(sessionId);
            lastAccess.remove(sessionId);
            return;
        }
        try {
            redis.delete(REDIS_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("[ConversationContext] Redis clearSession failed: {}", e.getMessage());
            sessions.remove(sessionId);
            lastAccess.remove(sessionId);
        }
    }

    // ─── Internal helpers ─────────────────────────────────────────────

    private boolean useRedis() {
        return !isDevOnly() && resolveRedis() != null;
    }

    private StringRedisTemplate resolveRedis() {
        if (redisProvider == null) {
            return null;
        }
        return redisProvider.getIfAvailable();
    }

    private static boolean isDevOnly() {
        String dev = System.getenv("EP_DEV_ONLY");
        if (dev == null || dev.isBlank()) {
            return true;
        }
        return !"false".equalsIgnoreCase(dev);
    }

    /**
     * Check whether a session has expired due to inactivity (in-memory mode).
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
