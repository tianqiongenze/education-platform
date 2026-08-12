package com.ep.boardgame.room;

import com.ep.boardgame.common.PlayerSide;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Transient;
import jakarta.persistence.Lob;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.ep.boardgame.common.DomainEvent;
import com.ep.boardgame.rule.Move;

/**
 * D-149: GameMatch aggregate root.
 * <p>
 * Represents an ongoing or completed Xiangqi match. Tracks the FEN board state,
 * move list (as JSON), and game result. Moves are validated by the
 * {@link com.ep.boardgame.rule.XiangqiRuleEngine} before being applied.
 */
@Entity
@Table(name = "bg_game_match")
public class GameMatch implements Serializable {

    @Id
    @Column(name = "match_id")
    private Long matchId;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "red_player_id", nullable = false)
    private Long redPlayerId;

    @Column(name = "black_player_id", nullable = false)
    private Long blackPlayerId;

    /** Current board state in FEN notation. */
    @Lob
    @Column(name = "fen_state", nullable = false)
    private String fenState;

    /** JSON array of all moves played so far. */
    @Lob
    @Column(name = "move_history")
    private String moveHistory = "[]";

    /** 0=ONGOING, 1=RED_WIN, 2=BLACK_WIN, 3=DRAW */
    @Column(name = "result", nullable = false)
    private Integer result;

    @Column(name = "move_count", nullable = false)
    private Integer moveCount = 0;

    @Column(name = "current_side", nullable = false)
    private Integer currentSide; // 0=RED, 1=BLACK

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "finished_at")
    private Long finishedAt;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    @Transient
    private transient List<DomainEvent> events = new ArrayList<>();

    // --- Result codes ---
    public static final int RESULT_ONGOING = 0;
    public static final int RESULT_RED_WIN = 1;
    public static final int RESULT_BLACK_WIN = 2;
    public static final int RESULT_DRAW = 3;

    // --- Lifecycle methods ---

    /**
     * Apply a validated move to the match.
     * @param newFen the new FEN after the move is applied by the rule engine
     * @param move the move that was made
     */
    public void applyMove(String newFen, Move move) {
        if (result != RESULT_ONGOING) {
            throw new IllegalStateException("Match is not ONGOING");
        }
        this.fenState = newFen;
        this.moveCount = move.moveNumber();
        this.currentSide = move.player().opponent() == PlayerSide.RED ? 0 : 1;
    }

    /**
     * Finish the match with a result.
     */
    public void finish(int result) {
        if (this.result != RESULT_ONGOING) {
            throw new IllegalStateException("Match already finished");
        }
        if (result < RESULT_RED_WIN || result > RESULT_DRAW) {
            throw new IllegalArgumentException("Invalid result code: " + result);
        }
        this.result = result;
        this.finishedAt = System.currentTimeMillis();
        this.events.add(new MatchFinishedEvent(matchId, result, System.currentTimeMillis()));
    }

    /**
     * Resign: the given player resigns, opponent wins.
     */
    public void resign(PlayerSide resigningSide) {
        if (result != RESULT_ONGOING) {
            throw new IllegalStateException("Match already finished");
        }
        this.result = resigningSide == PlayerSide.RED ? RESULT_BLACK_WIN : RESULT_RED_WIN;
        this.finishedAt = System.currentTimeMillis();
        this.events.add(new MatchFinishedEvent(matchId, result, System.currentTimeMillis()));
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> copy = new ArrayList<>(events);
        events.clear();
        return copy;
    }

    // --- Getters/Setters ---

    public Long getMatchId() { return matchId; }
    public void setMatchId(Long matchId) { this.matchId = matchId; }
    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public Long getRedPlayerId() { return redPlayerId; }
    public void setRedPlayerId(Long redPlayerId) { this.redPlayerId = redPlayerId; }
    public Long getBlackPlayerId() { return blackPlayerId; }
    public void setBlackPlayerId(Long blackPlayerId) { this.blackPlayerId = blackPlayerId; }
    public String getFenState() { return fenState; }
    public void setFenState(String fenState) { this.fenState = fenState; }
    public String getMoveHistory() { return moveHistory; }
    public void setMoveHistory(String moveHistory) { this.moveHistory = moveHistory; }
    public Integer getResult() { return result; }
    public void setResult(Integer result) { this.result = result; }
    public Integer getMoveCount() { return moveCount; }
    public void setMoveCount(Integer moveCount) { this.moveCount = moveCount; }
    public Integer getCurrentSide() { return currentSide; }
    public void setCurrentSide(Integer currentSide) { this.currentSide = currentSide; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }
    public Long getLockVersion() { return lockVersion; }
    public void setLockVersion(Long lockVersion) { this.lockVersion = lockVersion; }
}
