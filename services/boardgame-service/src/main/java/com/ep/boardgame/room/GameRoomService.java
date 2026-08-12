package com.ep.boardgame.room;

import com.ep.boardgame.common.PlayerSide;

import java.util.List;

/**
 * D-149: Service interface for game room + match operations.
 */
public interface GameRoomService {

    /** Create a new game room (WAITING state). */
    GameRoom createRoom(CreateRoomCmd cmd);

    /** Get a room by ID. */
    GameRoom getRoomById(Long roomId);

    /** List all rooms for a tenant. */
    List<GameRoom> listRooms(Long tenantId);

    /** List available (WAITING) rooms for a tenant. */
    List<GameRoom> listWaitingRooms(Long tenantId);

    /** A player joins a room. */
    GameRoom joinRoom(Long roomId, Long playerId);

    /** A player leaves a room. */
    GameRoom leaveRoom(Long roomId, Long playerId);

    /** Start the match — both players must be present. Returns the new GameMatch. */
    GameMatch startMatch(Long roomId);

    /** Make a move in a match. Returns the updated GameMatch. */
    GameMatch makeMove(MakeMoveCmd cmd);

    /** Resign from a match. */
    GameMatch resign(Long matchId, Long playerId);

    /** Get a match by ID. */
    GameMatch getMatchById(Long matchId);

    /** List matches for a player. */
    List<GameMatch> listMatchesByPlayer(Long playerId);

    /** List matches for a tenant. */
    List<GameMatch> listMatchesByTenant(Long tenantId);
}
