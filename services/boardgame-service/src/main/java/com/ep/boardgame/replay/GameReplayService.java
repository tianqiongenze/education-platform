package com.ep.boardgame.replay;

import com.ep.boardgame.common.SnowflakeIdGenerator;
import com.ep.boardgame.room.GameMatch;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * D-149: Replay service — creates and retrieves game replays.
 */
@DubboService
public class GameReplayService {

    private final GameReplayRepository replayRepo;

    public GameReplayService(GameReplayRepository replayRepo) {
        this.replayRepo = replayRepo;
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
        return replayRepo.save(replay);
    }

    public GameReplay getReplayByMatch(Long matchId) {
        List<GameReplay> replays = replayRepo.findByMatchId(matchId);
        return replays.isEmpty() ? null : replays.get(0);
    }

    public List<GameReplay> listReplaysByTenant(Long tenantId) {
        return replayRepo.findByTenantIdOrderByReplayIdDesc(tenantId);
    }

    public List<GameReplay> listReplaysByCourse(Long courseId) {
        return replayRepo.findByCourseIdOrderByReplayIdDesc(courseId);
    }

    /** Inner holder to avoid importing rule engine into the replay service directly. */
    static final class XiangqiRuleEngineHolder {
        static final String INITIAL_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
    }
}
