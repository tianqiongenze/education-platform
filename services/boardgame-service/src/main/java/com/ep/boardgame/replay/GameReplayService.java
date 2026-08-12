package com.ep.boardgame.replay;

import com.ep.boardgame.common.SnowflakeIdGenerator;
import com.ep.boardgame.room.GameMatch;
import com.ep.cache.CacheService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * D-149: Replay service — creates and retrieves game replays.
 * <p>
 * D-182: read methods cached via ep-cache SDK (L1 Caffeine + L2 Redis +
 * Pub/Sub broadcast). createReplay evicts the affected cache entries.
 */
@DubboService
public class GameReplayService {

    private static final String CACHE_REPLAY = "boardgame:replay";
    private static final String CACHE_REPLAY_BY_TENANT = "boardgame:replay:by-tenant";
    private static final String CACHE_REPLAY_BY_COURSE = "boardgame:replay:by-course";
    private static final long REPLAY_TTL_SEC = 600;

    private final GameReplayRepository replayRepo;
    private final CacheService cacheService;

    public GameReplayService(GameReplayRepository replayRepo, CacheService cacheService) {
        this.replayRepo = replayRepo;
        this.cacheService = cacheService;
    }

    @Transactional
    public GameReplay createReplay(GameMatch match, Long lessonId) {
        GameReplay replay = new GameReplay();
        replay.setReplayId(SnowflakeIdGenerator.getInstance().nextId());
        replay.setMatchId(match.getMatchId());
        replay.setTenantId(match.getTenantId());
        replay.setRoomId(match.getRoomId());
        replay.setCourseId(match.getCourseId());
        replay.setLessonId(lessonId);
        replay.setRedPlayerId(match.getRedPlayerId());
        replay.setBlackPlayerId(match.getBlackPlayerId());
        replay.setResult(match.getResult());
        replay.setMoveCount(match.getMoveCount());
        replay.setMoveHistory(match.getMoveHistory());
        replay.setInitialFen(XiangqiRuleEngineHolder.INITIAL_FEN);
        replay.setCreatedAt(System.currentTimeMillis());
        GameReplay saved = replayRepo.save(replay);
        cacheService.evict(CACHE_REPLAY, String.valueOf(match.getMatchId()));
        cacheService.evict(CACHE_REPLAY_BY_TENANT, String.valueOf(match.getTenantId()));
        cacheService.evict(CACHE_REPLAY_BY_COURSE, String.valueOf(match.getCourseId()));
        return saved;
    }

    public GameReplay getReplayByMatch(Long matchId) {
        return cacheService.getOrLoad(CACHE_REPLAY, String.valueOf(matchId),
                () -> {
                    List<GameReplay> replays = replayRepo.findByMatchId(matchId);
                    return replays.isEmpty() ? null : replays.get(0);
                }, REPLAY_TTL_SEC, true);
    }

    public List<GameReplay> listReplaysByTenant(Long tenantId) {
        return cacheService.getOrLoad(CACHE_REPLAY_BY_TENANT, String.valueOf(tenantId),
                () -> replayRepo.findByTenantIdOrderByReplayIdDesc(tenantId),
                REPLAY_TTL_SEC, false);
    }

    public List<GameReplay> listReplaysByCourse(Long courseId) {
        return cacheService.getOrLoad(CACHE_REPLAY_BY_COURSE, String.valueOf(courseId),
                () -> replayRepo.findByCourseIdOrderByReplayIdDesc(courseId),
                REPLAY_TTL_SEC, false);
    }

    /** Inner holder to avoid importing rule engine into the replay service directly. */
    static final class XiangqiRuleEngineHolder {
        static final String INITIAL_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
    }
}
