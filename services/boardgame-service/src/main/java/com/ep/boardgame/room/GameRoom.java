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
 * the lifecycle per ADR-BG10 (7-state machine):
 * <pre>
 *   CREATED → WAITING → READY → PLAYING → FINISHING → FINISHED
 *                                                  ↘ ABORTED (from any state)
 * </pre>
 * Two players join (WAITING→READY), the room transitions to PLAYING, then
 * FINISHING when the match concludes, and finally FINISHED. Any state may
 * transition to ABORTED (premature termination / abort).
 * <p>
 * The {@code int} status codes are kept for backward compatibility with
 * existing DB rows and {@code findByStatusAndTenantId} queries.
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

    /** 0=WAITING, 1=PLAYING, 2=FINISHED (legacy) */
    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "match_id")
    private Long matchId;

    /** D-192: BOARD_CAST ingest URL (set when a live broadcast is active). */
    @Column(name = "boardcast_ingest_url")
    private String boardcastIngestUrl;

    /** D-192: BOARD_CAST playback URL (set when a live broadcast is active). */
    @Column(name = "boardcast_play_url")
    private String boardcastPlayUrl;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    @Transient
    private transient List<DomainEvent> events = new ArrayList<>();

    // --- State codes (ADR-BG10 7-state machine) ---
    public static final int STATUS_CREATED = 0;
    public static final int STATUS_WAITING = 1;
    public static final int STATUS_READY = 2;
    public static final int STATUS_PLAYING = 3;
    public static final int STATUS_FINISHING = 4;
    public static final int STATUS_FINISHED = 5;
    public static final int STATUS_ABORTED = 6;

    // --- Legacy aliases (deprecated; kept for source compatibility) ---
    /** @deprecated use {@link #STATUS_WAITING} */
    @Deprecated
    public static final int STATUS_WAITING_LEGACY = 0;
    /** @deprecated use {@link #STATUS_PLAYING} */
    @Deprecated
    public static final int STATUS_PLAYING_LEGACY = 1;
    /** @deprecated use {@link #STATUS_FINISHED} */
    @Deprecated
    public static final int STATUS_FINISHED_LEGACY = 2;

    // --- Transition validation (ADR-BG10) ---

    /**
     * Returns true iff the current status may transition to {@code next}.
     * <ul>
     *   <li>CREATED → WAITING</li>
     *   <li>WAITING → READY</li>
     *   <li>READY → PLAYING</li>
     *   <li>PLAYING → FINISHING</li>
     *   <li>FINISHING → FINISHED</li>
     *   <li>any non-terminal → ABORTED</li>
     * </ul>
     * Same-status is not a transition. ABORTED and FINISHED are terminal.
     */
    public boolean canTransitionTo(int next) {
        if (next == this.status) return false;
        if (next == STATUS_ABORTED) {
            return this.status != STATUS_ABORTED && this.status != STATUS_FINISHED;
        }
        return switch (this.status) {
            case STATUS_CREATED -> next == STATUS_WAITING;
            case STATUS_WAITING -> next == STATUS_READY;
            case STATUS_READY -> next == STATUS_PLAYING;
            case STATUS_PLAYING -> next == STATUS_FINISHING;
            case STATUS_FINISHING -> next == STATUS_FINISHED;
            default -> false; // FINISHED / ABORTED are terminal
        };
    }

    private void requireTransition(int next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Illegal room transition: " + status + " → " + next);
        }
        this.status = next;
        this.updatedAt = System.currentTimeMillis();
    }

    // --- Lifecycle methods (state machine) ---

    /**
     * Move from CREATED to WAITING (open the room for joins).
     */
    public void open() {
        requireTransition(STATUS_WAITING);
    }

    /**
     * A player joins the room. Red joins first, then Black. Only valid in
     * WAITING state. When both players are present the room auto-transitions
     * to READY.
     */
    public void join(Long playerId) {
        if (status != STATUS_WAITING && status != STATUS_READY) {
            throw new IllegalStateException("Room is not WAITING/READY, cannot join");
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
        // Auto-advance WAITING → READY when both seats are filled.
        if (redPlayerId != null && blackPlayerId != null && status == STATUS_WAITING) {
            requireTransition(STATUS_READY);
        }
    }

    /**
     * A player leaves the room (only allowed in WAITING/READY state).
     * Leaving from READY reverts the room to WAITING.
     */
    public void leave(Long playerId) {
        if (status != STATUS_WAITING && status != STATUS_READY) {
            throw new IllegalStateException("Cannot leave a non-WAITING/READY room");
        }
        if (playerId.equals(redPlayerId)) {
            redPlayerId = null;
        } else if (playerId.equals(blackPlayerId)) {
            blackPlayerId = null;
        } else {
            throw new IllegalArgumentException("Player not in room");
        }
        // Revert READY → WAITING if a seat opens up.
        if (status == STATUS_READY && (redPlayerId == null || blackPlayerId == null)) {
            status = STATUS_WAITING;
        }
        updatedAt = System.currentTimeMillis();
    }

    /**
     * Start the match: both players must be present and the room must be READY.
     * Transitions READY → PLAYING.
     */
    public void start(Long matchId) {
        if (status != STATUS_READY) {
            throw new IllegalStateException("Room must be READY to start");
        }
        if (redPlayerId == null || blackPlayerId == null) {
            throw new IllegalStateException("Need both players to start");
        }
        this.matchId = matchId;
        requireTransition(STATUS_PLAYING);
        this.events.add(new RoomStartedEvent(roomId, matchId, System.currentTimeMillis()));
    }

    /**
     * Transition PLAYING → FINISHING (match is concluding, e.g. adjudicating result).
     */
    public void beginFinish() {
        requireTransition(STATUS_FINISHING);
    }

    /**
     * Transition FINISHING → FINISHED.
     */
    public void finish() {
        if (status != STATUS_FINISHING) {
            throw new IllegalStateException("Room must be FINISHING to finish");
        }
        requireTransition(STATUS_FINISHED);
        this.events.add(new RoomFinishedEvent(roomId, matchId, System.currentTimeMillis()));
    }

    /**
     * Abort the room from any non-terminal state. Terminal states (FINISHED,
     * ABORTED) cannot be aborted.
     */
    public void abort() {
        requireTransition(STATUS_ABORTED);
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
    public String getBoardcastIngestUrl() { return boardcastIngestUrl; }
    public void setBoardcastIngestUrl(String boardcastIngestUrl) { this.boardcastIngestUrl = boardcastIngestUrl; }
    public String getBoardcastPlayUrl() { return boardcastPlayUrl; }
    public void setBoardcastPlayUrl(String boardcastPlayUrl) { this.boardcastPlayUrl = boardcastPlayUrl; }
}
