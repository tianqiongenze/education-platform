package com.ep.boardgame.room;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.ep.boardgame.common.DomainEvent;
import com.ep.boardgame.common.PlayerSide;
import com.ep.boardgame.common.SnowflakeIdGenerator;
import com.ep.boardgame.event.PulsarEventPublisher;
import com.ep.boardgame.rule.Move;
import com.ep.boardgame.rule.XiangqiRuleEngine;
import com.ep.cache.CacheService;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * D-149: GameRoom + GameMatch service implementation.
 * <p>
 * Uses the XiangqiRuleEngine to validate moves before applying them to the
 * GameMatch aggregate. Events are pulled post-commit and published best-effort.
 * <p>
 * D-182: GET-by-id reads are cached via ep-cache SDK (L1 Caffeine + L2 Redis +
 * Pub/Sub broadcast). Writes evict the affected cache entries.
 */
@DubboService
public class GameRoomServiceImpl implements GameRoomService {

    private static final Logger log = LoggerFactory.getLogger(GameRoomServiceImpl.class);

    private static final String CACHE_GAME_ROOM = "boardgame:room";
    private static final String CACHE_GAME_MATCH = "boardgame:match";
    private static final long ROOM_TTL_SEC = 60;
    private static final long MATCH_TTL_SEC = 60;

    private final GameRoomRepository roomRepo;
    private final GameMatchRepository matchRepo;
    private final PulsarEventPublisher eventPublisher;
    private final XiangqiRuleEngine ruleEngine;
    private final CacheService cacheService;

    public GameRoomServiceImpl(GameRoomRepository roomRepo,
                               GameMatchRepository matchRepo,
                               PulsarEventPublisher eventPublisher,
                               CacheService cacheService) {
        this.roomRepo = roomRepo;
        this.matchRepo = matchRepo;
        this.eventPublisher = eventPublisher;
        this.cacheService = cacheService;
        this.ruleEngine = new XiangqiRuleEngine();
    }

    @Override
    @SentinelResource(value = "createRoom", blockHandler = "createRoomBlockHandler", fallback = "createRoomFallback")
    @Transactional
    public GameRoom createRoom(CreateRoomCmd cmd) {
        if (cmd.getRoomName() == null || cmd.getRoomName().isBlank()) {
            throw new IllegalArgumentException("roomName must not be blank");
        }
        if (cmd.getTenantId() == null) {
            throw new NullPointerException("tenantId");
        }

        // D-221: probe-pattern idempotency — a retried create with the same
        // business key returns the original room instead of duplicating it.
        // Blank keys are normalised to null so they never enter the unique index.
        String idemKey = cmd.getIdempotencyKey() == null || cmd.getIdempotencyKey().isBlank()
                ? null : cmd.getIdempotencyKey().trim();
        if (idemKey != null) {
            GameRoom existing = roomRepo
                    .findByTenantIdAndIdempotencyKey(cmd.getTenantId(), idemKey)
                    .orElse(null);
            if (existing != null) {
                log.info("D-221: createRoom idempotent replay for tenantId={} key={}: returning roomId={}",
                        cmd.getTenantId(), idemKey, existing.getRoomId());
                return existing;
            }
        }

        GameRoom room = new GameRoom();
        room.setRoomId(SnowflakeIdGenerator.getInstance().nextId());
        room.setTenantId(cmd.getTenantId());
        room.setCourseId(cmd.getCourseId());
        room.setLessonId(cmd.getLessonId());
        room.setRoomName(cmd.getRoomName());
        room.setIdempotencyKey(idemKey);
        room.setStatus(GameRoom.STATUS_CREATED);
        room.setCreatedAt(System.currentTimeMillis());
        room.setUpdatedAt(System.currentTimeMillis());
        room.open(); // CREATED → WAITING
        GameRoom saved = roomRepo.save(room);
        cacheService.evict(CACHE_GAME_ROOM, String.valueOf(saved.getRoomId()));
        return saved;
    }

    @Override
    public GameRoom getRoomById(Long roomId) {
        return cacheService.getOrLoad(CACHE_GAME_ROOM, String.valueOf(roomId),
                () -> roomRepo.findById(roomId).orElse(null), ROOM_TTL_SEC, true);
    }

    @Override
    public List<GameRoom> listRooms(Long tenantId) {
        if (tenantId == null) return roomRepo.findAllByOrderByRoomIdDesc();
        return roomRepo.findByTenantIdOrderByRoomIdDesc(tenantId);
    }

    @Override
    public List<GameRoom> listWaitingRooms(Long tenantId) {
        return roomRepo.findByStatusAndTenantId(GameRoom.STATUS_WAITING, tenantId);
    }

    @Override
    @SentinelResource(value = "joinRoom")
    @Transactional
    public GameRoom joinRoom(Long roomId, Long playerId) {
        GameRoom room = roomRepo.findById(roomId)
                .orElseThrow(() -> new IllegalStateException("Room not found: " + roomId));
        room.join(playerId);
        GameRoom saved = roomRepo.save(room);
        cacheService.evict(CACHE_GAME_ROOM, String.valueOf(roomId));
        return saved;
    }

    @Override
    @SentinelResource(value = "leaveRoom")
    @Transactional
    public GameRoom leaveRoom(Long roomId, Long playerId) {
        GameRoom room = roomRepo.findById(roomId)
                .orElseThrow(() -> new IllegalStateException("Room not found: " + roomId));
        room.leave(playerId);
        GameRoom saved = roomRepo.save(room);
        cacheService.evict(CACHE_GAME_ROOM, String.valueOf(roomId));
        return saved;
    }

    @Override
    @SentinelResource(value = "startMatch")
    @Transactional
    public GameMatch startMatch(Long roomId) {
        GameRoom room = roomRepo.findById(roomId)
                .orElseThrow(() -> new IllegalStateException("Room not found: " + roomId));

        Long matchId = SnowflakeIdGenerator.getInstance().nextId();
        room.start(matchId);
        roomRepo.save(room);
        cacheService.evict(CACHE_GAME_ROOM, String.valueOf(roomId));
        publishEvents(room);

        // Create the match
        GameMatch match = new GameMatch();
        match.setMatchId(matchId);
        match.setRoomId(room.getRoomId());
        match.setTenantId(room.getTenantId());
        match.setCourseId(room.getCourseId());
        match.setRedPlayerId(room.getRedPlayerId());
        match.setBlackPlayerId(room.getBlackPlayerId());
        match.setFenState(XiangqiRuleEngine.INITIAL_FEN);
        match.setMoveHistory("[]");
        match.setResult(GameMatch.RESULT_ONGOING);
        match.setMoveCount(0);
        match.setCurrentSide(0); // RED moves first
        match.setCreatedAt(System.currentTimeMillis());
        return matchRepo.save(match);
    }

    @Override
    @SentinelResource(value = "makeMove")
    @Transactional
    public GameMatch makeMove(MakeMoveCmd cmd) {
        GameMatch match = matchRepo.findById(cmd.getMatchId())
                .orElseThrow(() -> new IllegalStateException("Match not found: " + cmd.getMatchId()));
        if (match.getResult() != GameMatch.RESULT_ONGOING) {
            throw new IllegalStateException("Match is not ONGOING");
        }

        // Determine player side
        PlayerSide side = determineSide(match, cmd.getPlayerId());
        int nextMoveNumber = match.getMoveCount() + 1;
        Move move = cmd.toMove(side, nextMoveNumber);

        // Validate and apply via rule engine
        if (!ruleEngine.isLegalMove(match.getFenState(), move)) {
            throw new IllegalArgumentException("Illegal move: " + move.toUci());
        }
        String newFen = ruleEngine.applyMove(match.getFenState(), move);
        match.applyMove(newFen, move);

        // Check game result
        String result = ruleEngine.checkResult(newFen);
        if ("RED_WIN".equals(result)) {
            match.finish(GameMatch.RESULT_RED_WIN);
        } else if ("BLACK_WIN".equals(result)) {
            match.finish(GameMatch.RESULT_BLACK_WIN);
        } else if (ruleEngine.isCheckmate(newFen)) {
            // If checkmate, the side to move (after the current move) is checkmated
            int winner = side == PlayerSide.RED ? GameMatch.RESULT_RED_WIN : GameMatch.RESULT_BLACK_WIN;
            match.finish(winner);
        }

        matchRepo.save(match);
        publishEvents(match);
        cacheService.evict(CACHE_GAME_MATCH, String.valueOf(match.getMatchId()));
        return match;
    }

    @Override
    @SentinelResource(value = "resign")
    @Transactional
    public GameMatch resign(Long matchId, Long playerId) {
        GameMatch match = matchRepo.findById(matchId)
                .orElseThrow(() -> new IllegalStateException("Match not found: " + matchId));
        PlayerSide side = determineSide(match, playerId);
        match.resign(side);
        matchRepo.save(match);
        publishEvents(match);
        cacheService.evict(CACHE_GAME_MATCH, String.valueOf(matchId));
        return match;
    }

    @Override
    public GameMatch getMatchById(Long matchId) {
        return cacheService.getOrLoad(CACHE_GAME_MATCH, String.valueOf(matchId),
                () -> matchRepo.findById(matchId).orElse(null), MATCH_TTL_SEC, true);
    }

    @Override
    public List<GameMatch> listMatchesByPlayer(Long playerId) {
        return matchRepo.findByRedPlayerIdOrBlackPlayerIdOrderByMatchIdDesc(playerId, playerId);
    }

    @Override
    public List<GameMatch> listMatchesByTenant(Long tenantId) {
        return matchRepo.findByTenantIdOrderByMatchIdDesc(tenantId);
    }

    // --- Helpers ---

    private PlayerSide determineSide(GameMatch match, Long playerId) {
        if (playerId.equals(match.getRedPlayerId())) return PlayerSide.RED;
        if (playerId.equals(match.getBlackPlayerId())) return PlayerSide.BLACK;
        throw new IllegalArgumentException("Player " + playerId + " is not in match " + match.getMatchId());
    }

    private void publishEvents(GameRoom room) {
        for (DomainEvent e : room.pullEvents()) {
            log.info("D-149: domain event recorded: {}", e);
            eventPublisher.publish(e);
        }
    }

    private void publishEvents(GameMatch match) {
        for (DomainEvent e : match.pullEvents()) {
            log.info("D-149: domain event recorded: {}", e);
            eventPublisher.publish(e);
        }
    }

    // --- Sentinel block handlers / fallbacks ---

    public GameRoom createRoomBlockHandler(CreateRoomCmd cmd, Exception ex) {
        log.warn("createRoom blocked by Sentinel: {}", ex.getMessage());
        throw new IllegalStateException("Room creation blocked by Sentinel", ex);
    }

    public GameRoom createRoomFallback(CreateRoomCmd cmd, Throwable t) {
        log.warn("createRoom fallback: {}", t.getMessage());
        throw new IllegalStateException("Room creation unavailable", t);
    }
}
