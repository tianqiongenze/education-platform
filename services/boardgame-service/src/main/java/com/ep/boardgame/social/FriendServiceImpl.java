package com.ep.boardgame.social;

import com.ep.boardgame.room.GameMatch;
import com.ep.boardgame.room.GameMatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * D-192: JdbcTemplate-backed {@link FriendService}.
 * <p>
 * Uses raw SQL against the {@code bg_friendship} table (and
 * {@link GameMatchRepository} for match history) to keep the social feature
 * decoupled from the JPA entity manager when doing set operations like
 * mutual-friends.
 */
@Service
public class FriendServiceImpl implements FriendService {

    private static final Logger log = LoggerFactory.getLogger(FriendServiceImpl.class);

    private final JdbcTemplate jdbc;
    private final GameMatchRepository matchRepo;

    public FriendServiceImpl(JdbcTemplate jdbc, GameMatchRepository matchRepo) {
        this.jdbc = jdbc;
        this.matchRepo = matchRepo;
    }

    @Override
    @Transactional
    public Friendship addFriend(Long playerId, Long friendId) {
        if (playerId.equals(friendId)) {
            throw new IllegalArgumentException("Cannot friend yourself");
        }
        long now = System.currentTimeMillis();
        // Insert (or reactivate) the outbound edge.
        jdbc.update("""
                INSERT INTO bg_friendship (player_id, friend_id, status, created_at, updated_at)
                VALUES (?, ?, 'PENDING', ?, ?)
                ON DUPLICATE KEY UPDATE status = 'PENDING', updated_at = VALUES(updated_at)
                """, playerId, friendId, now, now);
        Friendship f = new Friendship();
        f.setPlayerId(playerId);
        f.setFriendId(friendId);
        f.setStatus(Friendship.Status.PENDING);
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        log.info("D-192: friend request {} -> {}", playerId, friendId);
        return f;
    }

    @Override
    @Transactional
    public void removeFriend(Long playerId, Long friendId) {
        int rows = jdbc.update(
                "DELETE FROM bg_friendship WHERE (player_id=? AND friend_id=?) OR (player_id=? AND friend_id=?)",
                playerId, friendId, friendId, playerId);
        log.info("D-192: removed {} friendship row(s) between {} and {}", rows, playerId, friendId);
    }

    @Override
    @Transactional
    public Friendship acceptFriend(Long playerId, Long friendId) {
        long now = System.currentTimeMillis();
        // Mark the inbound request ACCEPTED and create the reverse ACCEPTED edge.
        int updated = jdbc.update(
                "UPDATE bg_friendship SET status='ACCEPTED', updated_at=? WHERE player_id=? AND friend_id=? AND status='PENDING'",
                now, friendId, playerId);
        if (updated == 0) {
            throw new IllegalStateException("No pending request from " + friendId + " to " + playerId);
        }
        jdbc.update("""
                INSERT INTO bg_friendship (player_id, friend_id, status, created_at, updated_at)
                VALUES (?, ?, 'ACCEPTED', ?, ?)
                ON DUPLICATE KEY UPDATE status='ACCEPTED', updated_at=VALUES(updated_at)
                """, playerId, friendId, now, now);
        Friendship f = new Friendship();
        f.setPlayerId(playerId);
        f.setFriendId(friendId);
        f.setStatus(Friendship.Status.ACCEPTED);
        f.setUpdatedAt(now);
        return f;
    }

    @Override
    public List<Friendship> listFriends(Long playerId) {
        return jdbc.query("""
                SELECT player_id, friend_id, status, created_at, updated_at
                FROM bg_friendship
                WHERE (player_id=? OR friend_id=?) AND status='ACCEPTED'
                """, (rs, rowNum) -> {
            Friendship f = new Friendship();
            f.setPlayerId(rs.getLong("player_id"));
            f.setFriendId(rs.getLong("friend_id"));
            f.setStatus(Friendship.Status.valueOf(rs.getString("status")));
            f.setCreatedAt(rs.getLong("created_at"));
            f.setUpdatedAt(rs.getLong("updated_at"));
            return f;
        }, playerId, playerId);
    }

    @Override
    public List<Long> listMutualFriends(Long playerId, Long otherPlayerId) {
        // Friend ids of playerId (ACCEPTED).
        Set<Long> mine = new HashSet<>(friendIds(playerId));
        // Friend ids of otherPlayerId (ACCEPTED).
        Set<Long> theirs = new HashSet<>(friendIds(otherPlayerId));
        mine.retainAll(theirs);
        return new ArrayList<>(mine);
    }

    @Override
    public List<GameMatch> getMatchHistory(Long playerId, Long otherPlayerId) {
        List<GameMatch> mine = matchRepo.findByRedPlayerIdOrBlackPlayerIdOrderByMatchIdDesc(playerId, playerId);
        List<GameMatch> result = new ArrayList<>();
        for (GameMatch m : mine) {
            if (playerId.equals(m.getRedPlayerId()) && otherPlayerId.equals(m.getBlackPlayerId())
                    || playerId.equals(m.getBlackPlayerId()) && otherPlayerId.equals(m.getRedPlayerId())) {
                result.add(m);
            }
        }
        return result;
    }

    private List<Long> friendIds(Long playerId) {
        return jdbc.queryForList("""
                SELECT CASE WHEN player_id=? THEN friend_id ELSE player_id END AS fid
                FROM bg_friendship
                WHERE (player_id=? OR friend_id=?) AND status='ACCEPTED'
                """, Long.class, playerId, playerId, playerId);
    }
}
