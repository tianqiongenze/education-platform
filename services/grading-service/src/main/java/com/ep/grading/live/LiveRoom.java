package com.ep.grading.live;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * D-187: LiveRoom aggregate root.
 * <p>
 * Tracks a live streaming room lifecycle:
 * CREATED → PREPARING → LIVE → PAUSED → LIVE → ENDED → ARCHIVED.
 * <p>
 * ID is application-assigned (Snowflake) by the service layer before persist,
 * consistent with other grading-service aggregates (see GradePipeline). H2
 * ddl-auto=create-drop generates the column as bigint, compatible with both
 * H2 (dev-local) and TiDB (prod).
 */
@Entity
@Table(name = "live_room")
public class LiveRoom implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Mode { SRS, SFU }
    public enum Status { CREATED, PREPARING, LIVE, PAUSED, ENDED, ARCHIVED }

    @Id
    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false)
    private Mode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.CREATED;

    @Column(name = "ingest_url")
    private String ingestUrl;

    @Column(name = "play_url")
    private String playUrl;

    @Column(name = "sfu_room_id")
    private String sfuRoomId;

    @Column(name = "record_uri")
    private String recordUri;

    @Column(name = "viewer_limit", nullable = false)
    private Integer viewerLimit;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Integer version;

    public LiveRoom() {
    }

    public LiveRoom(Long roomId, Long tenantId, Long userId, Mode mode, Integer viewerLimit,
                    LocalDateTime createdAt) {
        this.roomId = roomId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.mode = mode;
        this.viewerLimit = viewerLimit;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = Status.CREATED;
    }

    // --- State machine transitions -------------------------------------------

    /**
     * Allowed transitions:
     * CREATED→PREPARING, PREPARING→LIVE, LIVE→PAUSED, PAUSED→LIVE,
     * LIVE→ENDED, PAUSED→ENDED, ENDED→ARCHIVED.
     */
    public boolean canTransitionTo(Status next) {
        if (next == null) return false;
        if (this.status == next) return false;
        return switch (this.status) {
            case CREATED -> next == Status.PREPARING;
            case PREPARING -> next == Status.LIVE;
            case LIVE -> next == Status.PAUSED || next == Status.ENDED;
            case PAUSED -> next == Status.LIVE || next == Status.ENDED;
            case ENDED -> next == Status.ARCHIVED;
            case ARCHIVED -> false;
        };
    }

    private void transition(Status next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Illegal transition: " + status + " → " + next);
        }
        this.status = next;
        this.updatedAt = LocalDateTime.now();
    }

    public void prepare() { transition(Status.PREPARING); }
    public void goLive() { transition(Status.LIVE); }
    public void pause() { transition(Status.PAUSED); }
    public void resume() { transition(Status.LIVE); }
    public void end() {
        if (status != Status.LIVE && status != Status.PAUSED) {
            throw new IllegalStateException(
                    "Illegal end transition: " + status + " → ENDED");
        }
        this.status = Status.ENDED;
        this.updatedAt = LocalDateTime.now();
    }
    public void archive() { transition(Status.ARCHIVED); }

    // --- Getters / Setters ---------------------------------------------------

    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getIngestUrl() { return ingestUrl; }
    public void setIngestUrl(String ingestUrl) { this.ingestUrl = ingestUrl; }
    public String getPlayUrl() { return playUrl; }
    public void setPlayUrl(String playUrl) { this.playUrl = playUrl; }
    public String getSfuRoomId() { return sfuRoomId; }
    public void setSfuRoomId(String sfuRoomId) { this.sfuRoomId = sfuRoomId; }
    public String getRecordUri() { return recordUri; }
    public void setRecordUri(String recordUri) { this.recordUri = recordUri; }
    public Integer getViewerLimit() { return viewerLimit; }
    public void setViewerLimit(Integer viewerLimit) { this.viewerLimit = viewerLimit; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LiveRoom that)) return false;
        return Objects.equals(roomId, that.roomId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomId);
    }
}
