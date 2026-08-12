package com.ep.boardgame.room;

import com.ep.boardgame.common.DomainEvent;
import com.ep.boardgame.common.PlayerSide;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Transient;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * D-149: GameRoom aggregate root.
 * <p>
 * A game room binds a course/lesson (Match-as-Lesson pattern) and manages
 * the lifecycle: WAITING → PLAYING → FINISHED. Two players join, the room
 * transitions to PLAYING, then FINISHED when the match completes.
 */
@Entity
@Table(name = "bg_game_room")
public class GameRoom implements Serializable {

    @Id
    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "lesson_id")
    private Long lessonId;

    @Column(name = "room_name", nullable = false)
    private String roomName;

    @Column(name = "red_player_id")
    private Long redPlayerId;

    @Column(name = "black_player_id")
    private Long blackPlayerId;

    @Column(name = "max_players", nullable = false)
    private Integer maxPlayers = 2;

    /** 0=WAITING, 1=PLAYING, 2=FINISHED */
    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "match_id")
    private Long matchId;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    @Transient
    private transient List<DomainEvent> events = new ArrayList<>();

    // --- State codes ---
    public static final int STATUS_WAITING = 0;
    public static final int STATUS_PLAYING = 1;
    public static final int STATUS_FINISHED = 2;

    // --- Lifecycle methods (state machine) ---

    /**
     * A player joins the room. Red joins first, then Black.
     */
    public void join(Long playerId) {
        if (status != STATUS_WAITING) {
            throw new IllegalStateException("Room is not WAITING, cannot join");
        }
        if (redPlayerId == null) {
            redPlayerId = playerId;
        } else if (blackPlayerId == null) {
            if (playerId.equals(redPlayerId)) {
                throw new IllegalArgumentException("Player already in room as RED");
            }
            blackPlayerId = playerId;
        } else {
            throw new IllegalStateException("Room is full");
        }
        updatedAt = System.currentTimeMillis();
    }

    /**
     * A player leaves the room (only allowed in WAITING state).
     */
    public void leave(Long playerId) {
        if (status != STATUS_WAITING) {
            throw new IllegalStateException("Cannot leave a non-WAITING room");
        }
        if (playerId.equals(redPlayerId)) {
            redPlayerId = null;
        } else if (playerId.equals(blackPlayerId)) {
            blackPlayerId = null;
        } else {
            throw new IllegalArgumentException("Player not in room");
        }
        updatedAt = System.currentTimeMillis();
    }

    /**
     * Start the match: both players must be present.
     */
    public void start(Long matchId) {
        if (status != STATUS_WAITING) {
            throw new IllegalStateException("Room must be WAITING to start");
        }
        if (redPlayerId == null || blackPlayerId == null) {
            throw new IllegalStateException("Need both players to start");
        }
        this.matchId = matchId;
        this.status = STATUS_PLAYING;
        this.updatedAt = System.currentTimeMillis();
        this.events.add(new RoomStartedEvent(roomId, matchId, System.currentTimeMillis()));
    }

    /**
     * Finish the room after match completion.
     */
    public void finish() {
        if (status != STATUS_PLAYING) {
            throw new IllegalStateException("Room must be PLAYING to finish");
        }
        this.status = STATUS_FINISHED;
        this.updatedAt = System.currentTimeMillis();
        this.events.add(new RoomFinishedEvent(roomId, matchId, System.currentTimeMillis()));
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> copy = new ArrayList<>(events);
        events.clear();
        return copy;
    }

    // --- Getters/Setters ---

    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public Long getLessonId() { return lessonId; }
    public void setLessonId(Long lessonId) { this.lessonId = lessonId; }
    public String getRoomName() { return roomName; }
    public void setRoomName(String roomName) { this.roomName = roomName; }
    public Long getRedPlayerId() { return redPlayerId; }
    public void setRedPlayerId(Long redPlayerId) { this.redPlayerId = redPlayerId; }
    public Long getBlackPlayerId() { return blackPlayerId; }
    public void setBlackPlayerId(Long blackPlayerId) { this.blackPlayerId = blackPlayerId; }
    public Integer getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(Integer maxPlayers) { this.maxPlayers = maxPlayers; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getMatchId() { return matchId; }
    public void setMatchId(Long matchId) { this.matchId = matchId; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getLockVersion() { return lockVersion; }
    public void setLockVersion(Long lockVersion) { this.lockVersion = lockVersion; }
}
