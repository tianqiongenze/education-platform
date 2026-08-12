package com.ep.boardgame.social;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.util.Objects;

/**
 * D-192: Friendship relationship between two players.
 * <p>
 * Composite key (playerId, friendId). status tracks the lifecycle of the
 * relationship: PENDING → ACCEPTED, or BLOCKED.
 */
@Entity
@Table(name = "bg_friendship")
@IdClass(Friendship.PK.class)
public class Friendship implements Serializable {

    public enum Status { PENDING, ACCEPTED, BLOCKED }

    public static class PK implements Serializable {
        private Long playerId;
        private Long friendId;

        public PK() {}
        public PK(Long playerId, Long friendId) {
            this.playerId = playerId;
            this.friendId = friendId;
        }
        public Long getPlayerId() { return playerId; }
        public void setPlayerId(Long playerId) { this.playerId = playerId; }
        public Long getFriendId() { return friendId; }
        public void setFriendId(Long friendId) { this.friendId = friendId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(playerId, pk.playerId) && Objects.equals(friendId, pk.friendId);
        }
        @Override
        public int hashCode() { return Objects.hash(playerId, friendId); }
    }

    @Id
    @Column(name = "player_id")
    private Long playerId;

    @Id
    @Column(name = "friend_id")
    private Long friendId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Version
    @Column(name = "lock_version")
    private Long lockVersion;

    public void accept() {
        if (status != Status.PENDING) {
            throw new IllegalStateException("Friendship not PENDING, was " + status);
        }
        this.status = Status.ACCEPTED;
        this.updatedAt = System.currentTimeMillis();
    }

    public void block() {
        this.status = Status.BLOCKED;
        this.updatedAt = System.currentTimeMillis();
    }

    // --- Getters/Setters ---

    public Long getPlayerId() { return playerId; }
    public void setPlayerId(Long playerId) { this.playerId = playerId; }
    public Long getFriendId() { return friendId; }
    public void setFriendId(Long friendId) { this.friendId = friendId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getLockVersion() { return lockVersion; }
    public void setLockVersion(Long lockVersion) { this.lockVersion = lockVersion; }
}
