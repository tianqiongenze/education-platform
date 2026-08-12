package com.ep.boardgame.replay;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Lob;

import java.io.Serializable;

/**
 * D-149: GameReplay entity — stores the full move history for review/learning.
 * <p>
 * In the Match-as-Lesson pattern, replays serve as learning materials: students
 * review grandmaster games or their own matches step-by-step.
 */
@Entity
@Table(name = "bg_game_replay")
public class GameReplay implements Serializable {

    @Id
    @Column(name = "replay_id")
    private Long replayId;

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "lesson_id")
    private Long lessonId;

    @Column(name = "red_player_id", nullable = false)
    private Long redPlayerId;

    @Column(name = "black_player_id", nullable = false)
    private Long blackPlayerId;

    /** Final result: 1=RED_WIN, 2=BLACK_WIN, 3=DRAW */
    @Column(name = "result", nullable = false)
    private Integer result;

    /** Total moves played. */
    @Column(name = "move_count", nullable = false)
    private Integer moveCount;

    /** Full move history as JSON array. */
    @Lob
    @Column(name = "move_history", nullable = false)
    private String moveHistory;

    /** Initial FEN (usually the standard starting position). */
    @Lob
    @Column(name = "initial_fen")
    private String initialFen;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    // --- Getters/Setters ---

    public Long getReplayId() { return replayId; }
    public void setReplayId(Long replayId) { this.replayId = replayId; }
    public Long getMatchId() { return matchId; }
    public void setMatchId(Long matchId) { this.matchId = matchId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public Long getLessonId() { return lessonId; }
    public void setLessonId(Long lessonId) { this.lessonId = lessonId; }
    public Long getRedPlayerId() { return redPlayerId; }
    public void setRedPlayerId(Long redPlayerId) { this.redPlayerId = redPlayerId; }
    public Long getBlackPlayerId() { return blackPlayerId; }
    public void setBlackPlayerId(Long blackPlayerId) { this.blackPlayerId = blackPlayerId; }
    public Integer getResult() { return result; }
    public void setResult(Integer result) { this.result = result; }
    public Integer getMoveCount() { return moveCount; }
    public void setMoveCount(Integer moveCount) { this.moveCount = moveCount; }
    public String getMoveHistory() { return moveHistory; }
    public void setMoveHistory(String moveHistory) { this.moveHistory = moveHistory; }
    public String getInitialFen() { return initialFen; }
    public void setInitialFen(String initialFen) { this.initialFen = initialFen; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getLockVersion() { return lockVersion; }
    public void setLockVersion(Long lockVersion) { this.lockVersion = lockVersion; }
}
