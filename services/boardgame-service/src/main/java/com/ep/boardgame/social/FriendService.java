package com.ep.boardgame.social;

import com.ep.boardgame.room.GameMatch;

import java.util.List;

/**
 * D-192: Social/friends service contract.
 */
public interface FriendService {

    /** Send a friend request (status PENDING). */
    Friendship addFriend(Long playerId, Long friendId);

    /** Remove a friendship (or cancel a pending request). */
    void removeFriend(Long playerId, Long friendId);

    /** Accept a pending friend request. */
    Friendship acceptFriend(Long playerId, Long friendId);

    /** List a player's accepted friends. */
    List<Friendship> listFriends(Long playerId);

    /** List mutual friends between two players. */
    List<Long> listMutualFriends(Long playerId, Long otherPlayerId);

    /** Get the match history between two players. */
    List<GameMatch> getMatchHistory(Long playerId, Long otherPlayerId);
}
