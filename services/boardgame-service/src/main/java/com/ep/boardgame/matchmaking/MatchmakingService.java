package com.ep.boardgame.matchmaking;

import com.ep.boardgame.common.SnowflakeIdGenerator;
import com.ep.boardgame.room.CreateRoomCmd;
import com.ep.boardgame.room.GameRoom;
import com.ep.boardgame.room.GameRoomService;
import com.ep.boardgame.tournament.Tournament.GameType;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * D-191: Skill-based matchmaking using a Redis sorted set per game type.
 * <p>
 * Queue key: {@code mm:queue:{gameType}}. Member = playerId, score = ELO rating.
 * <p>
 * Per Iron Law 1, all queue state lives in Redis (no in-process map). The
 * service is stateless and horizontally scalable.
 * <p>
 * Match strategy:
 * <ol>
 *   <li>{@link #joinQueue} — ZADD the player with their current rating.</li>
 *   <li>{@link #tryMatch} — scan members within ±200 rating of the lowest-scored
 *       queued player; if a pair is found, remove both and create a GameRoom.</li>
 *   <li>{@link #leaveQueue} — ZREM a player.</li>
 *   <li>Stale entries (queued &gt; 30s) are matched against a bot via
 *       {@link #tryMatchWithBotTimeout}.</li>
 * </ol>
 */
@DubboService
public class MatchmakingService {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingService.class);

    /** Rating window for an acceptable match. */
    static final int RATING_WINDOW = 200;
    /** Queue timeout before a bot match is offered (seconds). */
    static final long QUEUE_TIMEOUT_SEC = 30;
    /** Bot player id used for timeout matches. */
    public static final long BOT_PLAYER_ID = -1L;

    private static final String QUEUE_KEY_PREFIX = "mm:queue:";
    private static final String QUEUE_TIME_KEY_PREFIX = "mm:queuetime:";

    private final StringRedisTemplate redis;
    private final GameRoomService gameRoomService;

    public MatchmakingService(StringRedisTemplate redis, GameRoomService gameRoomService) {
        this.redis = redis;
        this.gameRoomService = gameRoomService;
    }

    private String queueKey(GameType gameType) {
        return QUEUE_KEY_PREFIX + gameType.name();
    }

    private String queueTimeKey(GameType gameType) {
        return QUEUE_TIME_KEY_PREFIX + gameType.name();
    }

    /**
     * Add a player to the matchmaking queue with their current ELO rating.
     *
     * @return true if the player was newly queued.
     */
    public boolean joinQueue(Long playerId, GameType gameType, int rating) {
        if (playerId == null || gameType == null) {
            throw new IllegalArgumentException("playerId and gameType required");
        }
        String qKey = queueKey(gameType);
        String tKey = queueTimeKey(gameType);
        // Record queue-join timestamp (for timeout detection).
        redis.opsForValue().set(tKey + ":" + playerId,
                String.valueOf(System.currentTimeMillis()));
        Boolean added = redis.opsForZSet().add(qKey, playerId.toString(), rating);
        return added != null && added;
    }

    /**
     * Remove a player from the queue.
     */
    public boolean leaveQueue(Long playerId, GameType gameType) {
        if (playerId == null || gameType == null) return false;
        redis.delete(queueTimeKey(gameType) + ":" + playerId);
        Long removed = redis.opsForZSet().remove(queueKey(gameType), playerId.toString());
        return removed != null && removed > 0;
    }

    /**
     * Attempt to find a match within the rating window. If found, both players
     * are removed from the queue and a GameRoom is created.
     *
     * @return the created GameRoom, or empty if no match was found.
     */
    public Optional<GameRoom> tryMatch(GameType gameType) {
        String qKey = queueKey(gameType);
        // Peek the lowest-rated queued player.
        Set<String> lowest = redis.opsForZSet().range(qKey, 0, 0);
        if (lowest == null || lowest.isEmpty()) {
            return Optional.empty();
        }
        String firstMember = lowest.iterator().next();
        Double firstScore = redis.opsForZSet().score(qKey, firstMember);
        if (firstScore == null) {
            return Optional.empty();
        }
        double minScore = firstScore - RATING_WINDOW;
        double maxScore = firstScore + RATING_WINDOW;
        // Find candidates within the window (excluding the player themselves).
        Set<String> candidates = redis.opsForZSet().rangeByScore(qKey, minScore, maxScore);
        if (candidates == null || candidates.size() < 2) {
            return Optional.empty();
        }
        // The first member is the seed; find a different opponent.
        String opponent = null;
        for (String c : candidates) {
            if (!c.equals(firstMember)) {
                opponent = c;
                break;
            }
        }
        if (opponent == null) {
            return Optional.empty();
        }
        // Atomically remove both. ZREM returns count removed.
        Long removedFirst = redis.opsForZSet().remove(qKey, firstMember);
        Long removedOpp = redis.opsForZSet().remove(qKey, opponent);
        if (removedFirst == null || removedFirst == 0 || removedOpp == null || removedOpp == 0) {
            // Lost a race; another worker already matched one of them.
            log.warn("matchmaking race: first={} opp={}", firstMember, opponent);
            return Optional.empty();
        }
        redis.delete(queueTimeKey(gameType) + ":" + firstMember);
        redis.delete(queueTimeKey(gameType) + ":" + opponent);

        Long redPlayer = Long.parseLong(firstMember);
        Long blackPlayer = Long.parseLong(opponent);
        GameRoom room = createRoomForMatch(redPlayer, blackPlayer, gameType);
        return Optional.of(room);
    }

    /**
     * If a player has been queued longer than {@link #QUEUE_TIMEOUT_SEC}, remove
     * them and create a GameRoom with a bot opponent.
     *
     * @return the created GameRoom, or empty if no timed-out player was found.
     */
    public Optional<GameRoom> tryMatchWithBotTimeout(GameType gameType) {
        String qKey = queueKey(gameType);
        Set<String> all = redis.opsForZSet().range(qKey, 0, -1);
        if (all == null || all.isEmpty()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        for (String member : all) {
            String tKey = queueTimeKey(gameType) + ":" + member;
            String ts = redis.opsForValue().get(tKey);
            if (ts == null) continue;
            long joinedAt = Long.parseLong(ts);
            if ((now - joinedAt) / 1000 >= QUEUE_TIMEOUT_SEC) {
                // Remove the timed-out player.
                redis.opsForZSet().remove(qKey, member);
                redis.delete(tKey);
                Long playerId = Long.parseLong(member);
                GameRoom room = createRoomForMatch(playerId, BOT_PLAYER_ID, gameType);
                log.info("matchmaking: player {} matched with bot (timeout)", playerId);
                return Optional.of(room);
            }
        }
        return Optional.empty();
    }

    private GameRoom createRoomForMatch(Long redPlayer, Long blackPlayer, GameType gameType) {
        CreateRoomCmd cmd = new CreateRoomCmd();
        cmd.setTenantId(0L); // matchmaking rooms are tenant-agnostic
        cmd.setRoomName("mm-" + gameType.name() + "-" + SnowflakeIdGenerator.getInstance().nextId());
        GameRoom room = gameRoomService.createRoom(cmd);
        gameRoomService.joinRoom(room.getRoomId(), redPlayer);
        gameRoomService.joinRoom(room.getRoomId(), blackPlayer);
        return gameRoomService.getRoomById(room.getRoomId());
    }
}
